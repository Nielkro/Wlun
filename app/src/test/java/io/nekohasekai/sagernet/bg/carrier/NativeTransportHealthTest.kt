package app.qthrone.mobile.bg.carrier

import org.junit.Assert.*
import org.junit.Test

class NativeTransportHealthTest {
    @Test fun transientAuthFailureWithoutWorkersRestartsAfterGraceAndCaptchaPausesWatchdog() {
        var now=0L
        val health=NativeTransportHealth { now }
        assertEquals(NativeConnectionStage.WAITING_VK,health.accept("error_code=9 Flood control"))
        health.accept("[ГРУППА #1] Ошибка кредов: captcha attempts exhausted")
        now=89_999;assertFalse(health.needsRestart(false,true))
        now=90_000;assertFalse(health.needsRestart(true,true))
        now=179_999;assertFalse(health.needsRestart(false,true))
        now=180_000;assertTrue(health.needsRestart(false,true))
    }
    @Test fun noTrafficDoesNotMeanNoWorkersAndCsqttZeroWorkersRecovers() {
        var now=0L
        val health=NativeTransportHealth { now }
        health.accept("[CSQTT diagnostic] active=9 up=0 down=0")
        now=600_000;assertFalse(health.needsRestart(false,false))
        assertEquals(NativeConnectionStage.RECONNECTING,health.accept("[CSQTT diagnostic] active=0 up=0 down=0"))
        now+=90_000;assertTrue(health.needsRestart(false,false))
        assertEquals(NativeConnectionStage.CONNECTED,health.accept("[ВОРКЕР #1] [READY]"))
        assertFalse(health.needsRestart(false,false))
    }
    @Test fun csqttPublicFatalMessagesStayFatalEvenAfterTransientCaptchaErrors() {
        assertNotNull(NativeSessionPolicy.startupFailure("[CSQTT] Сервер отказал в авторизации CSQTT. Проверьте пароль"))
        assertNotNull(NativeSessionPolicy.startupFailure("Пароль CSQTT привязан к другому устройству"))
        assertNotNull(NativeSessionPolicy.startupFailure("Клиент и сервер CSQTT несовместимы"))
        assertNull(NativeSessionPolicy.startupFailure("error_code=9 Flood control"))
    }
}
