package com.example.mafiagame.user.domain;

public enum UserTitle {
    ROOKIE("신입 시민"),
    SURVIVOR("생존 전문가"),
    STRATEGIST("전략가"),
    ACE("승부사"),
    LEGEND("전설");

    private final String displayName;

    UserTitle(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static UserTitle fromStats(int playCount, double winRate) {
        if (playCount >= 200 && winRate >= 0.65) {
            return LEGEND;
        }
        if (playCount >= 100 && winRate >= 0.58) {
            return ACE;
        }
        if (playCount >= 50 && winRate >= 0.52) {
            return STRATEGIST;
        }
        if (playCount >= 20) {
            return SURVIVOR;
        }
        return ROOKIE;
    }
}
