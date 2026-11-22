package com.protobench.data

import com.protobench.data.service.ComplexDataProtoBuilder
import com.protobench.data.service.DataService
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * 난수 시드를 고정했으므로 서비스를 새로 만들어도(= 서버를 다시 띄워도) 같은 데이터가 나온다.
 * 측정은 회차마다 서버를 새로 띄우므로 회차 사이에 데이터가 달라지면 안 된다.
 */
class DataSeedTest {

    private fun newService() = DataService().also { it.init() }

    @ParameterizedTest
    @ValueSource(strings = ["1kb", "10kb", "50kb", "100kb", "200kb", "500kb", "1mb"])
    fun `크기 축 - 두 번 만든 서비스의 바이트가 같다`(size: String) {
        assertArrayEquals(newService().getPayload(size), newService().getPayload(size), size)
    }

    @ParameterizedTest
    @ValueSource(strings = ["simple", "medium", "complex", "ultra", "extreme"])
    fun `복잡도 축 - 두 번 만든 서비스의 데이터와 Protobuf 바이트가 같다`(complexity: String) {
        val first = newService()
        val second = newService()
        assertEquals(first.getComplexData(complexity), second.getComplexData(complexity), complexity)

        // 응답의 timestamp는 요청마다 달라지는 값이라 같은 값을 넣어 비교한다
        val firstBytes = ComplexDataProtoBuilder(first).buildFrom("req", 0L, first.getComplexData(complexity)!!).toByteArray()
        val secondBytes = ComplexDataProtoBuilder(second).buildFrom("req", 0L, second.getComplexData(complexity)!!).toByteArray()
        assertArrayEquals(firstBytes, secondBytes, complexity)
    }
}
