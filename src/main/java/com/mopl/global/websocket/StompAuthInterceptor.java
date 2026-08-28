package com.mopl.global.websocket;

import com.mopl.domain.conversation.application.port.in.GetConversationUseCase;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.exception.MoplException;
import com.mopl.global.jwt.AuthTokenService;
import com.mopl.global.jwt.JwtClaims;
import com.mopl.global.jwt.JwtProvider;
import java.util.Collections;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class StompAuthInterceptor implements ChannelInterceptor {
  private final JwtProvider jwtProvider;
  private final GetConversationUseCase getConversationUseCase;
  private final AuthTokenService authTokenService;

  @Override
  public Message<?> preSend(Message<?> message , MessageChannel channel){
    StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);


    if(StompCommand.CONNECT.equals(accessor.getCommand())){

      try{
        String token = extractToken(accessor);
        JwtClaims claims = jwtProvider.parse(token);

        if (isBlacklisted(claims)) {
          log.warn("WebSocket 인증 실패 - 블랙리스트 처리된 토큰, jti: {}", claims.getTokenId());
          throw new MoplException(ErrorCode.INVALID_TOKEN);
        }

      // STOMP는 CONNECT/SUBSCRIBE/SEND마다 accessor가 별도로 만들어지고 Message가 불변 객체라,
      // accessor.setUser()로 세팅한 Principal이 이후 프레임에는 전달되지 않는다 -
      // 세션 동안 유지되는 sessionAttributes에 직접 담아 SUBSCRIBE 시점에 재사용한다.
      accessor.getSessionAttributes().put("claims", claims);

      var authentication = new UsernamePasswordAuthenticationToken(
          claims,
          null,
          Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + claims.getRole()))
      );
      accessor.setUser(authentication);

      // accessor.setUser() 호출만으로는 원본 Message(불변 객체)에 반영되지 않아,
      // 갱신된 헤더로 새 Message를 만들어 반환해야 실제로 인증 정보가 전달된다.
      return MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders());
    }catch (MoplException e) {
          log.warn("WebSocket 인증 실패 - {}" , e.getMessage());
          throw new MessagingException("WebSocket 인증에 실패했습니다." + e.getMessage());
    }catch (Exception e) {
          log.warn("WebSocket 인증 중 예상하지 못한 오류", e);
          throw new MessagingException("WebSocket 인증에 실패했습니다.");
        }
      }
    if(StompCommand.SUBSCRIBE.equals(accessor.getCommand())){
    validateSubscription(accessor);

    }
    return message;
  }


  private boolean isBlacklisted(JwtClaims claims) {
    try {
      return authTokenService.isBlacklistedJti(claims.getTokenId());
    } catch (Exception e) {
      log.error("블랙리스트 조회 실패 - fail-open으로 통과 처리, jti: {}", claims.getTokenId(), e);
      return false;
    }
  }

  private String extractToken(StompHeaderAccessor accessor){
    String authHeader = accessor.getFirstNativeHeader("Authorization");
    if(authHeader == null || !authHeader.startsWith("Bearer ")){
      throw new MoplException(ErrorCode.TOKEN_NOT_FOUND);
    }
    return authHeader.substring(7);
  }

  private void validateSubscription(StompHeaderAccessor accessor) {
    String destination = accessor.getDestination();

    if (destination != null && destination.matches("/sub/conversations/[^/]+/direct-messages")) {
      UUID conversationId = extractConversationId(destination);

      JwtClaims claims = (JwtClaims) accessor.getSessionAttributes().get("claims");
      if (claims == null) {
        log.warn("WebSocket 구독 거부 - 인증 정보 없음, destination: {}", destination);
        throw new MessagingException("인증되지 않은 구독 요청입니다.");
      }
      UUID myId = claims.getUserId();

      getConversationUseCase.getById(conversationId, myId);
    }
  }
  private UUID extractConversationId(String destination){
    String[] parts = destination.split("/");
    return UUID.fromString(parts[3]);
  }
}
