package com.mopl.domain.content.repository;

import com.mopl.domain.content.domain.Content;
import com.mopl.domain.content.domain.ContentSortField;
import com.mopl.domain.content.domain.ContentType;
import com.mopl.domain.content.dto.ContentSearchRequest;
import com.mopl.domain.content.infrastructure.ContentJpaEntity;
import com.mopl.domain.content.infrastructure.ContentMapper;
import com.mopl.domain.content.infrastructure.ContentTagJpaEntity;
import com.mopl.domain.content.infrastructure.QContentJpaEntity;
import com.mopl.domain.content.infrastructure.QContentTagJpaEntity;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.exception.MoplException;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * [Adapter Out] ContentRepository 구현체
 *
 * 순수 도메인 ↔ JPA 엔티티 변환을 ContentMapper가 담당
 * Service는 Content(순수 도메인)만 다루고
 * JPA 기술은 이 클래스 안에서만 사용됨
 *
 * 동적 검색/커서 쿼리는 QueryDSL로 작성 (다른 도메인과 통일)
 */
@Repository
@RequiredArgsConstructor
public class ContentRepositoryImpl implements ContentRepository {

  private final ContentJpaRepository jpaRepository;
  private final ContentMapper contentMapper;  // MapStruct 생성 구현체 주입
  private final JPAQueryFactory queryFactory;

  /**
   * watcherCount 등 실시간 데이터는 캐시 제외
   * Content 도메인 자체(제목/설명/평점 등)만 캐싱
   * 실시간 값은 ContentService에서 캐시된 결과에 매번 라이브로 합성.
   */
  @Override
  @CacheEvict(value = "content", key = "#domain.id")
  public Content save(Content domain) {
    // 기존에 저장된 콘텐츠면 관리 중인 엔티티를 그대로 갱신 (신규 엔티티로 통째로
    // 갈아끼우면 태그 컬렉션이 매번 새 객체가 되어 merge 시 삭제보다 삽입이 먼저 실행되고,
    // 겹치는 태그가 있을 때 content_tags(content_id, tag) 유니크 제약 위반이 남)
    Optional<ContentJpaEntity> existing = jpaRepository.findById(domain.getId());

    ContentJpaEntity entity;
    if (existing.isPresent()) {
      entity = existing.get();
      entity.update(domain.getType(), domain.getTitle(), domain.getDescription(),
          domain.getThumbnailUrl(), domain.getAverageRating(), domain.getReviewCount());
      entity.syncTags(domain.getTags());
    } else {
      // 신규 콘텐츠 → 도메인 → JPA 엔티티 변환 후 저장
      entity = contentMapper.toJpaEntity(domain);
    }

    ContentJpaEntity saved = jpaRepository.save(entity);
    // 저장된 JPA 엔티티 → 도메인으로 다시 변환해서 반환
    return contentMapper.toDomain(saved);
  }

  @Override
  @Cacheable(value = "content", key = "#id", sync = true)
  public Optional<Content> findById(UUID id) {
    return jpaRepository.findById(id)
        .map(contentMapper::toDomain);
  }

  @Override
  public Optional<Content> findByTypeAndExternalId(ContentType type, String externalId) {
    return jpaRepository.findByTypeAndExternalId(type, externalId)
        .map(contentMapper::toDomain);
  }

  @Override
  public Set<String> findExternalIdsByType(ContentType type) {
    return jpaRepository.findExternalIdsByType(type);
  }

  @Override
  public List<Content> findAllByType(ContentType type) {
    return toDomainListWithBulkTags(jpaRepository.findByType(type));
  }

  @Override
  public List<Content> findAllByCondition(ContentSearchRequest request) {
    QContentJpaEntity content = QContentJpaEntity.contentJpaEntity;
    ContentSortField sortField = ContentSortField.resolve(request.sortBy());
    boolean isAscending = "ASCENDING".equalsIgnoreCase(request.sortDirection());

    BooleanBuilder builder = filterPredicate(content, request, true);
    if (request.cursor() != null && request.idAfter() != null) {
      builder.and(cursorPredicate(content, sortField, request, isAscending));
    }

    List<ContentJpaEntity> entities = queryFactory.selectFrom(content)
        .where(builder)
        .orderBy(sortOrder(content, sortField, isAscending),
            isAscending ? content.id.asc() : content.id.desc())
        .limit(request.limit() + 1L) // limit+1개 조회 → hasNext 판단용
        .fetch();

    return toDomainListWithBulkTags(entities);
  }

  /**
   * content마다 tags를 개별 조회(지연 로딩)하지 않도록, 넘겨받은 엔티티 전체의 tags를
   * 한 번에 조회해 contentId 기준으로 묶은 뒤 도메인으로 변환한다 (N+1 방지)
   */
  private List<Content> toDomainListWithBulkTags(List<ContentJpaEntity> entities) {
    List<UUID> contentIds = entities.stream().map(ContentJpaEntity::getId).toList();
    Map<UUID, List<String>> tagsByContentId = findTagsByContentIds(contentIds);

    return entities.stream()
        .map(e -> contentMapper.toDomain(e, tagsByContentId.getOrDefault(e.getId(), List.of())))
        .toList();
  }

  /** contentIds에 속한 태그를 한 번에 조회해 contentId 기준으로 묶는다 (N+1 방지) */
  private Map<UUID, List<String>> findTagsByContentIds(List<UUID> contentIds) {
    if (contentIds.isEmpty()) {
      return Map.of();
    }
    QContentTagJpaEntity tag = QContentTagJpaEntity.contentTagJpaEntity;

    return queryFactory.selectFrom(tag)
        .where(tag.content.id.in(contentIds))
        .fetch()
        .stream()
        .collect(Collectors.groupingBy(
            t -> t.getContent().getId(),
            Collectors.mapping(ContentTagJpaEntity::getTag, Collectors.toList())
        ));
  }

  @Override
  public long countByCondition(ContentSearchRequest request) {
    QContentJpaEntity content = QContentJpaEntity.contentJpaEntity;

    // 커서는 빼고 필터 조건만으로 카운트 (페이지가 넘어가도 totalCount는 동일해야 함)
    Long count = queryFactory.select(content.count())
        .from(content)
        .where(filterPredicate(content, request, true))
        .fetchOne();

    return count != null ? count : 0L;
  }

  @Override
  @CacheEvict(value = "content", key = "#id")
  public void deleteById(UUID id) {
    jpaRepository.deleteById(id);
  }

  @Override
  @CacheEvict(value = "content", key = "#contentId")
  public void applyRatingDelta(UUID contentId, BigDecimal ratingDelta, int countDelta) {
    jpaRepository.applyRatingDelta(contentId, ratingDelta, countDelta);
  }

  @Override
  public boolean existsById(UUID id) {
    return jpaRepository.existsById(id);
  }

  @Override
  public List<Content> findAllByIds(List<UUID> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    return toDomainListWithBulkTags(jpaRepository.findAllById(ids));
  }

  /** 타입/키워드/태그 필터 조건 (커서 제외 - totalCount 계산에도 그대로 재사용됨) */
  private BooleanBuilder filterPredicate(
      QContentJpaEntity content,
      ContentSearchRequest request,
      boolean includeKeyword
  ) {

    BooleanBuilder builder = new BooleanBuilder();

    if (request.typeEqual() != null) {
      builder.and(content.type.eq(request.typeEqual()));
    }

    if (includeKeyword &&
        request.keywordLike() != null &&
        !request.keywordLike().isBlank()) {

      builder.and(
          content.title.lower()
              .like("%" + request.keywordLike().toLowerCase() + "%")
      );
    }

    if (request.tagsIn() != null && !request.tagsIn().isEmpty()) {
      QContentTagJpaEntity tag = QContentTagJpaEntity.contentTagJpaEntity;

      builder.and(
          JPAExpressions.selectOne()
              .from(tag)
              .where(
                  tag.content.eq(content)
                      .and(tag.tag.in(request.tagsIn()))
              )
              .exists()
      );
    }

    return builder;
  }

  /** sortField(정렬 기준과 커서 기준은 항상 같아야 함)에 맞는 정렬 표현식 */
  private OrderSpecifier<?> sortOrder(
      QContentJpaEntity content, ContentSortField sortField, boolean isAscending) {
    return switch (sortField) {
      case CREATED_AT -> isAscending ? content.createdAt.asc() : content.createdAt.desc();
      case REVIEW_COUNT -> isAscending ? content.reviewCount.asc() : content.reviewCount.desc();
      case AVERAGE_RATING -> isAscending ? content.averageRating.asc() : content.averageRating.desc();
    };
  }

  /** sortField별 커서 비교 (같으면 id로 순서 고정) */
  private BooleanExpression cursorPredicate(
      QContentJpaEntity content, ContentSortField sortField,
      ContentSearchRequest request, boolean isAscending) {
    return switch (sortField) {
      case CREATED_AT -> {
        Instant cursorTime;
        try {
          cursorTime = Instant.parse(request.cursor());
        } catch (DateTimeParseException e) {
          throw new MoplException(ErrorCode.INVALID_CURSOR_FORMAT);
        }
        yield isAscending
            ? content.createdAt.gt(cursorTime)
                .or(content.createdAt.eq(cursorTime).and(content.id.gt(request.idAfter())))
            : content.createdAt.lt(cursorTime)
                .or(content.createdAt.eq(cursorTime).and(content.id.lt(request.idAfter())));
      }
      case REVIEW_COUNT -> {
        int cursorValue;
        try {
          cursorValue = Integer.parseInt(request.cursor());
        } catch (NumberFormatException e) {
          throw new MoplException(ErrorCode.INVALID_CURSOR_FORMAT);
        }
        yield isAscending
            ? content.reviewCount.gt(cursorValue)
                .or(content.reviewCount.eq(cursorValue).and(content.id.gt(request.idAfter())))
            : content.reviewCount.lt(cursorValue)
                .or(content.reviewCount.eq(cursorValue).and(content.id.lt(request.idAfter())));
      }
      case AVERAGE_RATING -> {
        BigDecimal cursorValue;
        try {
          cursorValue = new BigDecimal(request.cursor());
        } catch (NumberFormatException e) {
          throw new MoplException(ErrorCode.INVALID_CURSOR_FORMAT);
        }
        yield isAscending
            ? content.averageRating.gt(cursorValue)
                .or(content.averageRating.eq(cursorValue).and(content.id.gt(request.idAfter())))
            : content.averageRating.lt(cursorValue)
                .or(content.averageRating.eq(cursorValue).and(content.id.lt(request.idAfter())));
      }
    };
  }

  @Override
  public List<Content> findAllByIdsWithCondition(
      List<UUID> ids,
      ContentSearchRequest request
  ) {

    if (ids == null || ids.isEmpty()) {
      return List.of();
    }

    QContentJpaEntity content = QContentJpaEntity.contentJpaEntity;

    ContentSortField sortField =
        ContentSortField.resolve(request.sortBy());

    boolean isAscending =
        "ASCENDING".equalsIgnoreCase(request.sortDirection());

    BooleanBuilder builder = idPredicate(content, ids);

    if (request.cursor() != null &&
        request.idAfter() != null) {

      builder.and(
          cursorPredicate(
              content,
              sortField,
              request,
              isAscending
          )
      );
    }

    List<ContentJpaEntity> entities =
        queryFactory
            .selectFrom(content)
            .where(builder)
            .orderBy(
                sortOrder(content, sortField, isAscending),
                isAscending
                    ? content.id.asc()
                    : content.id.desc()
            )
            .limit(request.limit() + 1L)
            .fetch();

    return entities.stream()
        .map(contentMapper::toDomain)
        .toList();
  }

  @Transactional(readOnly = true)
  @Override
  public List<Content> findAll() {
    return jpaRepository.findAll()
        .stream()
        .map(contentMapper::toDomain)
        .toList();
  }

  private BooleanBuilder idPredicate(QContentJpaEntity content, List<UUID> ids) {
    BooleanBuilder builder = new BooleanBuilder();
    builder.and(content.id.in(ids));
    return builder;
  }
}
