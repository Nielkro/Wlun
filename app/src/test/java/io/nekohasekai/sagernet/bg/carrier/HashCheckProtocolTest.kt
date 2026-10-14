package app.qthrone.mobile.bg.carrier

import org.junit.Assert.*
import org.junit.Test

class HashCheckProtocolTest {
    private val hashes=listOf(2 to "first_hash",4 to "second_hash")
    @Test fun nativeOrderMapsToNonemptyEditorSlots() {
        assertEquals(HashCheckProtocol.Update(4,"Проверяется…",false),HashCheckProtocol.parse("HASH_CHECK_START|2|second_hash",hashes))
        assertEquals(HashCheckProtocol.Update(2,"TURN доступен",true),HashCheckProtocol.parse("HASH_CHECK|1|first_hash|ok|TURN urls=2",hashes))
    }
    @Test fun rejectsUnrelatedAndMalformedResults() {
        for(line in listOf("HASH_CHECK|0|first_hash|ok|x","HASH_CHECK|3|first_hash|ok|x",
            "HASH_CHECK|1|other_hash|ok|x","HASH_CHECK|bad|first_hash|ok|x","unrelated output"))
            assertNull(HashCheckProtocol.parse(line,hashes))
    }
    @Test fun providerSecretsNeverEnterResultText() {
        val result=HashCheckProtocol.parse("HASH_CHECK|1|first_hash|error|access_token=secret https://vk.ru/",hashes)!!
        assertEquals("Диагностика не удалась",result.message)
        assertFalse(result.message.contains("secret"))
    }
}
