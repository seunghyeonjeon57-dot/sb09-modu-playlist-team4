package com.mopl.global.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mopl.domain.content.domain.Content;
import com.mopl.domain.conversation.domain.Conversation;
import com.mopl.domain.playlist.domain.Playlist;
import com.mopl.global.config.cachemixin.ContentCacheMixin;
import com.mopl.global.config.cachemixin.ConversationCacheMixin;
import com.mopl.global.config.cachemixin.PlaylistCacheMixin;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * CachingConfigurer는 cacheManager()/errorHandler() 등의 메서드 이름/시그니처가 인터페이스와 정확히 일치해야
 * Spring이 캐싱 aspect에 연결하는 유일한 통로가 된다
 * 그렇지 않으면 조용히 기본값(SimpleCacheErrorHandler 등)이 쓰인다.
 *
 * GenericJackson2JsonRedisSerializer()의 기본 생성자가 만드는 ObjectMapper는 JavaTimeModule이
 * 없어서 Instant/LocalDateTime 필드가 있는 도메인을 캐싱하면 직렬화가 그대로 실패한다
 * (예: Content/Playlist의 createdAt). 직접 ObjectMapper를 만들어 모듈을 등록하고,
 * 역직렬화 시 실제 타입을 복원하기 위한 다형성 타입 정보(default typing)도 그대로 유지한다.
 */
@Configuration
@EnableCaching
@Slf4j
@RequiredArgsConstructor
public class CacheConfig implements CachingConfigurer {

  private final RedisConnectionFactory connectionFactory;

  @Bean
  @Override
  public CacheManager cacheManager() {
    /**
     * GenericJackson2JsonRedisSerializer() 기본 생성자가 만드는
     * ObjectMapper에 JavaTimeModule이 없어서 직접 ObjectMapper를 만들어 모듈 등록
     * (Instant 필드도 캐싱 가능)
     */
    ObjectMapper redisObjectMapper = new ObjectMapper();
    redisObjectMapper.registerModule(new JavaTimeModule());
    redisObjectMapper.activateDefaultTyping(
        LaissezFaireSubTypeValidator.instance,
        ObjectMapper.DefaultTyping.NON_FINAL,
        JsonTypeInfo.As.PROPERTY
    );
    // Content/Playlist는 생성자가 private인 순수 도메인이라 Jackson이 기본적으로 못 만든다 -
    // restore(...) 팩토리 메서드를 생성자처럼 쓰도록 믹스인으로 알려준다 (도메인 클래스 자체는 안 건드림)
    redisObjectMapper.addMixIn(Content.class, ContentCacheMixin.class);
    redisObjectMapper.addMixIn(Playlist.class, PlaylistCacheMixin.class);
    redisObjectMapper.addMixIn(Conversation.class, ConversationCacheMixin.class);

    RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
        .entryTtl(Duration.ofMinutes(30))
        .serializeKeysWith(RedisSerializationContext.SerializationPair
            .fromSerializer(new StringRedisSerializer()))
        .serializeValuesWith(RedisSerializationContext.SerializationPair
            .fromSerializer(new GenericJackson2JsonRedisSerializer(redisObjectMapper)))
        .disableCachingNullValues();

    Map<String, RedisCacheConfiguration> cacheConfigs = new HashMap<>();
    cacheConfigs.put("userSummary", defaultConfig.entryTtl(Duration.ofHours(1)));
    // Conversation은 생성 후 참여자/생성일이 절대 안 바뀌는 사실상 불변 데이터라 TTL을 길게 둠
    cacheConfigs.put("conversation", defaultConfig.entryTtl(Duration.ofHours(1)));
    cacheConfigs.put("content", defaultConfig.entryTtl(Duration.ofMinutes(30)));
    cacheConfigs.put("playlist", defaultConfig.entryTtl(Duration.ofMinutes(10)));
    // 구독/팔로우 수는 콘텐츠/플레이리스트 메타데이터보다 더 자주 바뀌므로 TTL을 짧게 둠
    cacheConfigs.put("playlistSubscriberCount", defaultConfig.entryTtl(Duration.ofMinutes(5)));
    cacheConfigs.put("followerCount", defaultConfig.entryTtl(Duration.ofMinutes(5)));

    return RedisCacheManager.builder(connectionFactory)
        .cacheDefaults(defaultConfig)
        .withInitialCacheConfigurations(cacheConfigs)
        .transactionAware()
        .build();
  }

  @Bean
  @Override
  public CacheErrorHandler errorHandler() {
    return new CacheErrorHandler() {
      @Override
      public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        log.warn("캐시 조회 실패 - cache: {}, key: {}", cache.getName(), key, exception);
      }

      @Override
      public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
        log.warn("캐시 저장 실패 - cache: {}, key: {}", cache.getName(), key, exception);
      }

      @Override
      public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        log.warn("캐시 삭제 실패 - cache: {}, key: {}", cache.getName(), key, exception);
      }

      @Override
      public void handleCacheClearError(RuntimeException exception, Cache cache) {
        log.warn("캐시 전체 삭제 실패 - cache: {}", cache.getName(), exception);
      }
    };
  }
}