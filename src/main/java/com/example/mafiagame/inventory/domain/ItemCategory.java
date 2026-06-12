package com.example.mafiagame.inventory.domain;

/**
 * 아이템 카테고리.
 *
 * <p>
 * 게임 밸런스를 깨지 않는 비(非)Pay-to-Win 아이템 분류.
 * </p>
 */
public enum ItemCategory {

    /** 채팅 말풍선, 프로필 프레임 등 외형 아이템. */
    COSMETIC,

    /** 경험치 부스트 등 게임 밸런스 무관 아이템. */
    BOOST,

    /** 월간/시즌 정기 구독 상품. */
    SEASON_PASS,

    /** 인앱 가상 화폐 충전. */
    COIN
}
