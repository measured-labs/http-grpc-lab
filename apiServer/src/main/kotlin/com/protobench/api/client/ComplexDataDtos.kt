package com.protobench.api.client

// ============================================
// 복잡도 축 http-json 경로의 응답 DTO
//
// dataServer `service/DataService.kt`의 DTO와 같은 모양(이름, 필드, 타입)이다.
// Protobuf 경로가 응답을 타입이 있는 메시지 객체로 푸는 것과 같은 수준으로 맞추려고
// JSON을 Map이 아니라 이 클래스들로 바인딩한다. dataServer 쪽을 바꾸면 여기도 바꾼다.
// ============================================

/** 복잡도 축 JSON 응답 (data의 타입은 복잡도에 따라 다르다) */
data class ComplexDataJsonResponse<T : Any>(
    val requestId: String,
    val timestamp: Long,
    val serializedSize: Int,
    val complexity: String,
    val data: T
)

// ---- simple / medium / complex ----

data class ItemDto(val productId: String, val name: String, val quantity: Int, val price: Double)

data class OrderDto(val orderId: String, val amount: Double, val timestamp: Long, val items: List<ItemDto>)

data class AddressDto(val city: String, val street: String, val zipcode: String, val country: String)

data class SimpleDataDto(
    val id: String,
    val name: String,
    val age: Int,
    val score: Double,
    val isActive: Boolean
)

data class MediumDataDto(
    val id: String,
    val name: String,
    val age: Int,
    val score: Double,
    val isActive: Boolean,
    val email: String,
    val phone: String,
    val createdAt: Long,
    val updatedAt: Long,
    val status: String,
    val tags: List<String>,
    val address: AddressDto,
    val metadata: Map<String, String>
)

data class ComplexDataDto(
    val id: String,
    val name: String,
    val age: Int,
    val score: Double,
    val isActive: Boolean,
    val email: String,
    val phone: String,
    val createdAt: Long,
    val updatedAt: Long,
    val status: String,
    val tags: List<String>,
    val address: AddressDto,
    val billingAddress: AddressDto,
    val orders: List<OrderDto>,
    val metadata: Map<String, String>,
    val scores: Map<String, Int>,
    val permissions: List<String>,
    val addresses: List<AddressDto>,
    val description: String,
    val notes: String
)

// ---- ultra ----

data class ContactDto(val name: String, val phone: String, val email: String, val role: String)

data class AddressWithContactsDto(
    val city: String,
    val street: String,
    val zipcode: String,
    val country: String,
    val contacts: List<ContactDto>
)

data class AttributeDto(val key: String, val value: String, val type: String)

data class ItemWithAttributesDto(
    val productId: String,
    val name: String,
    val quantity: Int,
    val price: Double,
    val attributes: List<AttributeDto>
)

data class OrderWithAttributesDto(
    val orderId: String,
    val amount: Double,
    val timestamp: Long,
    val status: String,
    val items: List<ItemWithAttributesDto>
)

data class SubcategoryDto(val id: String, val name: String, val items: List<String>)

data class CategoryDto(
    val id: String,
    val name: String,
    val description: String,
    val subcategories: List<SubcategoryDto>
)

data class ChangeDto(val field: String, val oldValue: String, val newValue: String)

data class HistoryEntryDto(val id: String, val timestamp: Long, val action: String, val changes: List<ChangeDto>)

data class UltraDataDto(
    val id: String,
    val name: String,
    val age: Int,
    val score: Double,
    val isActive: Boolean,
    val email: String,
    val phone: String,
    val createdAt: Long,
    val updatedAt: Long,
    val status: String,
    val priority: Int,
    val level: Int,
    val rating: Double,
    val verified: Boolean,
    val premium: Boolean,
    val tags: List<String>,
    val permissions: List<String>,
    val metadata: Map<String, String>,
    val scores: Map<String, Int>,
    val addresses: List<AddressWithContactsDto>,
    val orders: List<OrderWithAttributesDto>,
    val categories: List<CategoryDto>,
    val history: List<HistoryEntryDto>,
    val description: String,
    val notes: String
)

// ---- extreme ----

data class AttributeValueDto(val value: String, val label: String, val isDefault: Boolean)

data class AttributeWithValuesDto(val key: String, val type: String, val values: List<AttributeValueDto>)

data class ExtremeItemDto(
    val productId: String,
    val name: String,
    val quantity: Int,
    val price: Double,
    val attributes: List<AttributeWithValuesDto>
)

data class ExtremeOrderDto(
    val orderId: String,
    val amount: Double,
    val timestamp: Long,
    val status: String,
    val priority: Int,
    val items: List<ExtremeItemDto>
)

data class MemberDto(val id: String, val name: String, val role: String, val email: String)

data class TeamDto(val id: String, val name: String, val members: List<MemberDto>)

data class DepartmentDto(val id: String, val name: String, val budget: Double, val teams: List<TeamDto>)

data class OrganizationDto(val id: String, val name: String, val type: String, val departments: List<DepartmentDto>)

data class RoleDto(val id: String, val name: String, val permissions: List<String>)

data class ParticipantDto(val id: String, val name: String, val email: String, val roles: List<RoleDto>)

data class EventDto(
    val id: String,
    val name: String,
    val timestamp: Long,
    val location: String,
    val participants: List<ParticipantDto>
)

data class ExtremeDataDto(
    val id: String,
    val name: String,
    val age: Int,
    val score: Double,
    val isActive: Boolean,
    val email: String,
    val phone: String,
    val createdAt: Long,
    val updatedAt: Long,
    val status: String,
    val priority: Int,
    val level: Int,
    val rating: Double,
    val verified: Boolean,
    val premium: Boolean,
    val tier: String,
    val region: String,
    val language: String,
    val currency: String,
    val timezone: String,
    val tags: List<String>,
    val permissions: List<String>,
    val metadata: Map<String, String>,
    val scores: Map<String, Int>,
    val addresses: List<AddressWithContactsDto>,
    val orders: List<ExtremeOrderDto>,
    val organizations: List<OrganizationDto>,
    val events: List<EventDto>,
    val description: String,
    val notes: String,
    val additionalInfo: String
)
