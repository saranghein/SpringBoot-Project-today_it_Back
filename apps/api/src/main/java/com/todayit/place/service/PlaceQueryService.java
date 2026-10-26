package com.todayit.place.service;

import com.todayit.common.pagination.PageResult;
import com.todayit.course.service.RegionPlaceQueryService;
import com.todayit.place.entity.Category;
import com.todayit.place.entity.Place;
import com.todayit.place.entity.PlaceImage;
import com.todayit.place.exception.PlaceNotFoundException;
import com.todayit.place.repository.PlaceImageRepository;
import com.todayit.place.repository.PlaceMemberLikeRepository;
import com.todayit.place.repository.PlaceRepository;
import com.todayit.place.repository.PlaceScrapRepository;
import com.todayit.place.service.model.PlaceImageResult;
import com.todayit.place.service.model.PlaceLocationResult;
import com.todayit.place.service.model.PlaceResult;
import com.todayit.place.service.model.PlaceScrapSort;
import com.todayit.place.service.model.PlaceSort;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 장소 조회 업무를 처리하는 Query Service입니다. */
@Service
public class PlaceQueryService implements PlaceScrapQueryService {

  private static final Logger LOG = LoggerFactory.getLogger(PlaceQueryService.class);

  private final PlaceMemberLikeRepository placeMemberLikeRepository;
  private final PlaceRepository placeRepository;
  private final PlaceImageRepository placeImageRepository;
  private final PlaceScrapRepository placeScrapRepository;
  private final RegionPlaceQueryService regionPlaceQueryService;

  /**
   * 장소 조회에 필요한 Repository를 받습니다.
   *
   * @param placeMemberLikeRepository 장소 좋아요 Repository
   * @param placeRepository 장소 Repository
   * @param placeImageRepository 장소 이미지 Repository
   * @param placeScrapRepository 장소 스크랩 Repository
   * @param regionPlaceQueryService 장소 필터용 지역 조회 Service 계약
   */
  public PlaceQueryService(
      PlaceMemberLikeRepository placeMemberLikeRepository,
      PlaceRepository placeRepository,
      PlaceImageRepository placeImageRepository,
      PlaceScrapRepository placeScrapRepository,
      RegionPlaceQueryService regionPlaceQueryService) {
    this.placeMemberLikeRepository = placeMemberLikeRepository;
    this.placeRepository = placeRepository;
    this.placeImageRepository = placeImageRepository;
    this.placeScrapRepository = placeScrapRepository;
    this.regionPlaceQueryService = regionPlaceQueryService;
  }

  /**
   * 활성화되고 삭제되지 않은 장소를 페이지 단위로 조회합니다. 장소 이미지도 페이지 내 장소를 기준으로 일괄 조회하여 결과에 포함합니다.
   *
   * @param sidoCode 시·도 코드 2자리, 생략 가능
   * @param sigunguCode 시·군·구 코드 3자리, 생략 가능
   * @param categories 장소 카테고리 목록
   * @param page 페이지 번호
   * @param size 페이지 크기
   * @param memberId 선택 회원 식별자, 비회원이면 null
   * @param sort 정렬 기준
   * @return 장소 목록과 페이지 정보
   */
  @Transactional(readOnly = true)
  public PageResult<PlaceResult> findPlaces(
      String sidoCode,
      String sigunguCode,
      List<Category> categories,
      int page,
      int size,
      PlaceSort sort,
      String memberId) {
    Sort order =
        sort == PlaceSort.LATEST
            ? Sort.by(Sort.Direction.DESC, "createdAt")
            : Sort.by(Sort.Direction.DESC, "viewCount");

    boolean regionFiltered = sidoCode != null || sigunguCode != null;
    List<Integer> regionIds =
        regionFiltered ? regionPlaceQueryService.findRegionIds(sidoCode, sigunguCode) : List.of(0);
    boolean categoryFiltered = categories != null && !categories.isEmpty();
    List<Category> categoryFilters =
        categoryFiltered ? List.copyOf(categories) : Arrays.asList(Category.values());
    Page<Place> placePage =
        placeRepository.findPlaces(
            regionIds,
            regionFiltered,
            categoryFilters,
            categoryFiltered,
            PageRequest.of(page, size, order));
    List<Place> places = placePage.getContent();
    Map<Integer, List<String>> imageUrlsByPlaceId = findImageUrlsByPlaceId(places);
    Set<Integer> likedPlaceIds =
        memberId == null || places.isEmpty()
            ? Set.of()
            : placeMemberLikeRepository.findLikedPlaceIds(
                memberId, places.stream().map(Place::getPlaceId).toList());
    List<PlaceResult> content =
        places.stream()
            .map(
                place -> {
                  PlaceResult result = toResult(place, imageUrlsByPlaceId);
                  return new PlaceResult(
                      result.placeId(),
                      result.name(),
                      result.latitude(),
                      result.longitude(),
                      result.address(),
                      result.category(),
                      result.viewCount(),
                      result.imageUrls(),
                      likedPlaceIds.contains(place.getPlaceId()));
                })
            .toList();

    LOG.info(
        "장소 목록 조회: regionFiltered={}, categoryFiltered={}, page={}, size={}, sort={}, resultCount={}",
        regionFiltered,
        categoryFiltered,
        page,
        size,
        sort,
        content.size());
    return new PageResult<>(
        content, placePage.getNumber(), placePage.getSize(), placePage.getTotalElements());
  }

  /**
   * 활성화되고 삭제되지 않은 장소의 지도 정보를 조회합니다.
   *
   * @param placeId 장소 식별자
   * @return 장소 식별자, 좌표와 주소
   * @throws PlaceNotFoundException 장소가 없거나 비활성·삭제 상태일 때
   */
  @Transactional(readOnly = true)
  public PlaceLocationResult findPlaceLocation(int placeId) {
    Place place =
        placeRepository
            .findByPlaceIdAndIsActiveTrueAndIsDeletedFalse(placeId)
            .orElseThrow(PlaceNotFoundException::new);

    LOG.info("장소 위치 조회: placeId={}", placeId);
    return PlaceLocationResult.from(place.getSnapshot());
  }

  /**
   * 코스 등 장소를 참조하는 기능에서 장소가 조회 가능한 상태인지 검증합니다.
   *
   * @param placeId 장소 식별자
   * @throws PlaceNotFoundException 장소가 없거나 비활성·삭제 상태일 때
   */
  @Transactional(readOnly = true)
  public void validatePlaceExists(int placeId) {
    placeRepository
        .findByPlaceIdAndIsActiveTrueAndIsDeletedFalse(placeId)
        .orElseThrow(PlaceNotFoundException::new);
  }

  /**
   * 활성화되고 삭제되지 않은 장소의 사진을 최신 등록순으로 페이지 단위 조회합니다.
   *
   * @param placeId 장소 식별자
   * @param page 페이지 번호
   * @param size 페이지 크기
   * @return 장소 사진 목록과 페이지 정보
   * @throws PlaceNotFoundException 장소가 없거나 비활성·삭제 상태일 때
   */
  @Transactional(readOnly = true)
  public PageResult<PlaceImageResult> findPlaceImages(int placeId, int page, int size) {
    placeRepository
        .findByPlaceIdAndIsActiveTrueAndIsDeletedFalse(placeId)
        .orElseThrow(PlaceNotFoundException::new);

    Page<PlaceImage> imagePage =
        placeImageRepository.findByPlacePlaceId(
            placeId,
            PageRequest.of(
                page,
                size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("placeImageId"))));

    LOG.info(
        "장소 사진 조회: placeId={}, page={}, size={}, resultCount={}",
        placeId,
        page,
        size,
        imagePage.getNumberOfElements());
    return new PageResult<>(
        imagePage.getContent().stream()
            .map(image -> new PlaceImageResult(image.getPlaceImageId(), image.getImageUrl()))
            .toList(),
        imagePage.getNumber(),
        imagePage.getSize(),
        imagePage.getTotalElements());
  }

  /**
   * 회원이 활성 상태로 스크랩한 장소를 페이지 단위로 조회합니다.
   *
   * @param memberId 회원 식별자
   * @param page 페이지 번호
   * @param size 페이지 크기
   * @param sort 스크랩 장소 정렬 기준
   * @return 스크랩 장소 페이지
   */
  @Transactional(readOnly = true)
  @Override
  public PageResult<PlaceResult> findScrappedPlaces(
      String memberId, int page, int size, PlaceScrapSort sort) {
    Page<Place> placePage =
        sort == PlaceScrapSort.OLDEST
            ? placeScrapRepository.findScrappedPlacesOldest(memberId, PageRequest.of(page, size))
            : placeScrapRepository.findScrappedPlacesLatest(memberId, PageRequest.of(page, size));
    List<Place> places = placePage.getContent();
    Map<Integer, List<String>> imageUrlsByPlaceId = findImageUrlsByPlaceId(places);
    List<PlaceResult> content =
        places.stream().map(place -> toResult(place, imageUrlsByPlaceId)).toList();

    LOG.info(
        "스크랩 장소 조회: page={}, size={}, sort={}, resultCount={}", page, size, sort, content.size());
    return new PageResult<>(
        content, placePage.getNumber(), placePage.getSize(), placePage.getTotalElements());
  }

  /**
   * 여러 장소의 이미지 URL을 장소 식별자별로 그룹화합니다. 빈 장소 목록이면 이미지 Repository를 호출하지 않습니다.
   *
   * @param places 이미지 URL을 조회할 장소 목록
   * @return 장소 식별자별 이미지 URL 목록
   */
  private Map<Integer, List<String>> findImageUrlsByPlaceId(List<Place> places) {
    if (places.isEmpty()) {
      return Map.of();
    }

    return placeImageRepository
        .findByPlacePlaceIdInOrderByPlacePlaceIdAscCreatedAtAsc(
            places.stream().map(Place::getPlaceId).toList())
        .stream()
        .collect(
            Collectors.groupingBy(
                PlaceImage::getPlaceId,
                Collectors.mapping(PlaceImage::getImageUrl, Collectors.toList())));
  }

  /**
   * 배치 조회한 이미지 URL을 포함해 장소 Entity를 서비스 결과로 변환합니다.
   *
   * @param place 변환할 장소
   * @param imageUrlsByPlaceId 장소 식별자별 이미지 URL 목록
   * @return 장소 조회 결과
   */
  private PlaceResult toResult(Place place, Map<Integer, List<String>> imageUrlsByPlaceId) {
    return PlaceResult.from(
        place.getSnapshot(imageUrlsByPlaceId.getOrDefault(place.getPlaceId(), List.of())));
  }
}
