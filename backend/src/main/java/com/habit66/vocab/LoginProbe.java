package com.habit66.vocab;

/** 로그인이 필요한 생성기가 로그인 상태를 스스로 점검한다(claude-cli). */
public interface LoginProbe {

    enum Result { OK, LOGIN_REQUIRED, UNKNOWN }

    /**
     * @param hint      사용자가 해야 할 일(LOGIN_REQUIRED) 또는 확인하지 못한 이유(UNKNOWN)
     * @param expiresAt 로그인 만료 예정 시각(epoch millis). 모르면 null
     */
    record LoginCheck(Result result, String hint, Long expiresAt) {

        static LoginCheck ok(Long expiresAt) {
            return new LoginCheck(Result.OK, null, expiresAt);
        }

        static LoginCheck loginRequired(String hint) {
            return new LoginCheck(Result.LOGIN_REQUIRED, hint, null);
        }

        /** 네트워크 끊김, 시간 초과처럼 로그인과 무관할 수 있는 실패. 상태를 바꾸지 않는다. */
        static LoginCheck unknown(String reason) {
            return new LoginCheck(Result.UNKNOWN, reason, null);
        }
    }

    /** 실제 호출을 한 번 하므로 몇 초 걸린다. */
    LoginCheck checkLogin();
}
