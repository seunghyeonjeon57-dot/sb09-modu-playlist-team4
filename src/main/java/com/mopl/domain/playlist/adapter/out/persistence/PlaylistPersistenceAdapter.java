package com.mopl.domain.playlist.adapter.out.persistence;

import com.mopl.domain.playlist.application.dto.PlaylistSearchCondition;
import com.mopl.domain.playlist.application.port.out.LoadPlaylistPort;
import com.mopl.domain.playlist.application.port.out.SavePlaylistPort;
import com.mopl.domain.playlist.domain.Playlist;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.exception.MoplException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class PlaylistPersistenceAdapter implements SavePlaylistPort, LoadPlaylistPort {

  private final PlaylistJpaRepository playlistJpaRepository;
  private final PlaylistSubscriptionJpaRepository subscriptionJpaRepository;
  private final PlaylistPersistenceMapper mapper;

  /**
   * isSubscribed 등 요청자별로 달라지는 값은 캐시적용 제외
   * Playlist 도메인 자체(제목/설명/updatedAt 등)만 캐싱되고,
   * 구독 여부는 PlaylistService에서 매번 라이브로 합성.
   */
  @Override
  @CacheEvict(value = "playlist", key = "#playlist.id")
  public Playlist save(Playlist playlist) {
    PlaylistJpaEntity existing = playlistJpaRepository.findById(playlist.getId()).orElse(null);

    PlaylistJpaEntity entity = mapper.toJpaEntity(playlist, existing);

    if (existing != null) {
      syncContents(entity, playlist);
    }

    PlaylistJpaEntity saved = playlistJpaRepository.save(entity);
    return mapper.toDomain(saved);
  }

  @Override
  @CacheEvict(value = "playlist", key = "#playlistId")
  public void delete(UUID playlistId) {
    subscriptionJpaRepository.deleteByPlaylistId(playlistId);
    playlistJpaRepository.deleteById(playlistId);
  }

  @Override
  @CacheEvict(value = "playlistSubscriberCount", key = "#playlistId")
  public void subscribe(UUID playlistId, UUID subscriberId) {
    PlaylistJpaEntity playlist = playlistJpaRepository.findById(playlistId)
        .orElseThrow(() -> new MoplException(ErrorCode.PLAYLIST_NOT_FOUND));

    PlaylistSubscriptionJpaEntity subscription = PlaylistSubscriptionJpaEntity.of(subscriberId);
    subscription.assignPlaylist(playlist);

    subscriptionJpaRepository.saveAndFlush(subscription);
  }

  @Override
  @Transactional
  @CacheEvict(value = "playlistSubscriberCount", key = "#playlistId")
  public void unsubscribe(UUID playlistId, UUID subscriberId) {
    subscriptionJpaRepository.deleteByPlaylistIdAndSubscriberId(playlistId, subscriberId);
  }

  @Override
  @Cacheable(value = "playlist", key = "#id", sync = true)
  public Optional<Playlist> findById(UUID id) {
    return playlistJpaRepository.findById(id).map(mapper::toDomain);
  }

  @Override
  public Optional<Playlist> findByIdForUpdate(UUID id) {
    return playlistJpaRepository.findByIdForUpdate(id).map(mapper::toDomain);
  }

  @Override
  public List<Playlist> findAllByCondition(PlaylistSearchCondition condition) {

    List<PlaylistJpaEntity> playlists = playlistJpaRepository.findAllWithCursor(condition);

    // playlist마다 contents를 개별 조회하지 않도록, 페이지 안 playlist 전체의 contents를
    // 한 번에 조회해 playlistId 기준으로 묶어둔다 (N+1 방지)
    List<UUID> playlistIds = playlists.stream().map(PlaylistJpaEntity::getId).toList();
    Map<UUID, List<PlaylistContentJpaEntity>> contentsByPlaylistId =
        playlistJpaRepository.findContentsByPlaylistIds(playlistIds).stream()
            .collect(Collectors.groupingBy(pc -> pc.getPlaylist().getId()));

    return playlists.stream()
        .map(p -> mapper.toDomain(p, contentsByPlaylistId.getOrDefault(p.getId(), List.of())))
        .toList();
  }

  @Override
  public long countByCondition(PlaylistSearchCondition condition) {
    return playlistJpaRepository.countAll(condition);
  }

  @Override
  @Cacheable(value = "playlistSubscriberCount", key = "#playlistId")
  public long countSubscribers(UUID playlistId) {
    return subscriptionJpaRepository.countByPlaylistId(playlistId);
  }

  @Override
  public boolean isSubscribed(UUID playlistId, UUID subscriberId) {
    return subscriptionJpaRepository.existsByPlaylistIdAndSubscriberId(playlistId, subscriberId);
  }

  @Override
  public List<UUID> findSubscriberIds(UUID playlistId) {
    return subscriptionJpaRepository.findByPlaylistId(playlistId).stream()
        .map(PlaylistSubscriptionJpaEntity::getSubscriberId)
        .toList();
  }

  @Override
  public Map<UUID, Long> countSubscribersBulk(List<UUID> playlistIds) {
    return subscriptionJpaRepository.countByPlaylistIds(playlistIds).stream()
        .collect(Collectors.toMap(
            PlaylistSubscriptionCount::getPlaylistId,
            PlaylistSubscriptionCount::getCount
        ));
  }

  @Override
  @Transactional
  public void removeContentFromAllPlaylists(UUID contentId) {
    // 어느 플레이리스트가 영향받는지 미리 알 수 없어 "playlist" 캐시를 콕 집어 evict하진 않는다.
    // findSummariesByIds가 존재하지 않는 contentId는 이미 조용히 걸러내므로(PlaylistService의
    // null 필터링), 캐시 TTL이 지날 때까지는 화면에서만 지연되어 사라지고 깨지진 않는다.
    playlistJpaRepository.deleteByContentId(contentId);
  }

  @Override
  public Map<UUID, Boolean> isSubscribedBulk(List<UUID> playlistIds, UUID subscriberId) {
    List<UUID> subscribedPlaylistIds = subscriptionJpaRepository.findSubscribedPlaylistIds(
        playlistIds, subscriberId);
    return playlistIds.stream()
        .collect(Collectors.toMap(
            id -> id,
            subscribedPlaylistIds::contains
        ));
  }

  private void syncContents(PlaylistJpaEntity entity, Playlist playlist) {

    List<PlaylistContentJpaEntity> contents = entity.getContents();

    contents.removeIf(content ->
        !playlist.getContentIds().contains(content.getContentId())
    );

    Map<UUID, PlaylistContentJpaEntity> existingMap =
        contents.stream()
            .collect(Collectors.toMap(
                PlaylistContentJpaEntity::getContentId,
                c -> c
            ));

    int position = 0;

    for (UUID contentId : playlist.getContentIds()) {

      PlaylistContentJpaEntity content = existingMap.get(contentId);

      if (content == null) {
        content = PlaylistContentJpaEntity.of(contentId, position);
        content.assignPlaylist(entity);
        contents.add(content);
      } else {
        content.updatePosition(position);
      }

      position++;
    }
  }
}