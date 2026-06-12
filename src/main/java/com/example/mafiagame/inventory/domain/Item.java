package com.example.mafiagame.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 판매 가능한 아이템 카탈로그 엔티티.
 *
 * <p>코스메틱, 부스트, 시즌패스, 코인 등 모든 상품을 관리한다.</p>
 */
@Entity
@Table(name = "items")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long itemId;

    /** 아이템 표시 이름. */
    @Column(nullable = false, length = 100)
    private String itemName;

    /** 아이템 설명. */
    @Column(length = 500)
    private String description;

    /** 아이템 카테고리. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ItemCategory category;

    /** 원(₩) 단위 가격. 코인으로 구매하는 경우 코인 가격. */
    @Column(nullable = false)
    private Integer price;

    /** 유효 기간(일). null이면 영구 아이템. */
    private Integer durationDays;

    /** 판매 활성화 여부. */
    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;
}
