package com.protobench.data.service

import com.protobench.proto.*
import org.springframework.stereotype.Component

/**
 * 복잡도 축의 Protobuf 응답 메시지 생성
 *
 * HTTP + Protobuf(`/data/complex/binary`)와 gRPC 단건(`GetComplexData`) 경로가
 * 같은 메시지를 보내도록 두 경로 모두 이 빌더로 만든다.
 */
@Component
class ComplexDataProtoBuilder(
    private val dataService: DataService
) {

    companion object {
        val COMPLEXITIES = listOf("simple", "medium", "complex", "ultra", "extreme")
    }

    /**
     * 복잡도에 맞는 데이터를 만들어 ComplexDataResponse로 빌드한다.
     *
     * @param requestId 요청 ID
     * @param complexity 복잡도 (simple, medium, complex, ultra, extreme — 대소문자 무시)
     * @return ComplexDataResponse, 복잡도 값이 잘못되면 null
     */
    fun build(requestId: String, complexity: String): ComplexDataResponse? {
        val timestamp = System.currentTimeMillis()
        return when (complexity.lowercase()) {
            "simple" -> buildSimpleResponse(requestId, timestamp)
            "medium" -> buildMediumResponse(requestId, timestamp)
            "complex" -> buildComplexResponse(requestId, timestamp)
            "ultra" -> buildUltraResponse(requestId, timestamp)
            "extreme" -> buildExtremeResponse(requestId, timestamp)
            else -> null
        }
    }

    /**
     * response_size에 자기 직렬화 크기(response_size 필드 제외)를 담아 다시 빌드한다.
     */
    private fun withResponseSize(builder: ComplexDataResponse.Builder): ComplexDataResponse {
        val response = builder.build()
        return builder.setResponseSize(response.serializedSize).build()
    }

    private fun buildSimpleResponse(requestId: String, timestamp: Long): ComplexDataResponse {
        val data = dataService.generateSimpleData(requestId)

        val simpleData = SimpleData.newBuilder()
            .setId(data.id)
            .setName(data.name)
            .setAge(data.age)
            .setScore(data.score)
            .setIsActive(data.isActive)
            .build()

        return withResponseSize(
            ComplexDataResponse.newBuilder()
                .setRequestId(requestId)
                .setTimestamp(timestamp)
                .setSimple(simpleData)
        )
    }

    private fun buildMediumResponse(requestId: String, timestamp: Long): ComplexDataResponse {
        val data = dataService.generateMediumData(requestId)

        val mediumData = MediumData.newBuilder()
            .setId(data.id)
            .setName(data.name)
            .setAge(data.age)
            .setScore(data.score)
            .setIsActive(data.isActive)
            .setEmail(data.email)
            .setPhone(data.phone)
            .setCreatedAt(data.createdAt)
            .setUpdatedAt(data.updatedAt)
            .setStatus(data.status)
            .addAllTags(data.tags)
            .setAddress(buildAddress(data.address))
            .putAllMetadata(data.metadata)
            .build()

        return withResponseSize(
            ComplexDataResponse.newBuilder()
                .setRequestId(requestId)
                .setTimestamp(timestamp)
                .setMedium(mediumData)
        )
    }

    private fun buildComplexResponse(requestId: String, timestamp: Long): ComplexDataResponse {
        val data = dataService.generateComplexData(requestId)

        val complexData = ComplexData.newBuilder()
            .setId(data.id)
            .setName(data.name)
            .setAge(data.age)
            .setScore(data.score)
            .setIsActive(data.isActive)
            .setEmail(data.email)
            .setPhone(data.phone)
            .setCreatedAt(data.createdAt)
            .setUpdatedAt(data.updatedAt)
            .setStatus(data.status)
            .addAllTags(data.tags)
            .setAddress(buildAddress(data.address))
            .setBillingAddress(buildAddress(data.billingAddress))
            .addAllOrders(data.orders.map { buildOrder(it) })
            .putAllMetadata(data.metadata)
            .putAllScores(data.scores)
            .addAllPermissions(data.permissions)
            .addAllAddresses(data.addresses.map { buildAddress(it) })
            .setDescription(data.description)
            .setNotes(data.notes)
            .build()

        return withResponseSize(
            ComplexDataResponse.newBuilder()
                .setRequestId(requestId)
                .setTimestamp(timestamp)
                .setComplex(complexData)
        )
    }

    /** Ultra: 3단계 중첩 (~200회 빌더 호출) */
    private fun buildUltraResponse(requestId: String, timestamp: Long): ComplexDataResponse {
        val data = dataService.generateUltraData(requestId)

        val ultraData = UltraData.newBuilder()
            // 기본 필드 15개
            .setId(data.id)
            .setName(data.name)
            .setAge(data.age)
            .setScore(data.score)
            .setIsActive(data.isActive)
            .setEmail(data.email)
            .setPhone(data.phone)
            .setCreatedAt(data.createdAt)
            .setUpdatedAt(data.updatedAt)
            .setStatus(data.status)
            .setPriority(data.priority)
            .setLevel(data.level)
            .setRating(data.rating)
            .setVerified(data.verified)
            .setPremium(data.premium)
            // 배열/맵
            .addAllTags(data.tags)
            .addAllPermissions(data.permissions)
            .putAllMetadata(data.metadata)
            .putAllScores(data.scores)
            // 2단계 중첩
            .addAllAddresses(data.addresses.map { buildAddressWithContacts(it) })
            // 3단계 중첩
            .addAllOrders(data.orders.map { buildOrderWithAttributes(it) })
            .addAllCategories(data.categories.map { buildCategory(it) })
            .addAllHistory(data.history.map { buildHistoryEntry(it) })
            .setDescription(data.description)
            .setNotes(data.notes)
            .build()

        return withResponseSize(
            ComplexDataResponse.newBuilder()
                .setRequestId(requestId)
                .setTimestamp(timestamp)
                .setUltra(ultraData)
        )
    }

    /** Extreme: 4단계 중첩 (~800회 빌더 호출) */
    private fun buildExtremeResponse(requestId: String, timestamp: Long): ComplexDataResponse {
        val data = dataService.generateExtremeData(requestId)

        val extremeData = ExtremeData.newBuilder()
            // 기본 필드 20개
            .setId(data.id)
            .setName(data.name)
            .setAge(data.age)
            .setScore(data.score)
            .setIsActive(data.isActive)
            .setEmail(data.email)
            .setPhone(data.phone)
            .setCreatedAt(data.createdAt)
            .setUpdatedAt(data.updatedAt)
            .setStatus(data.status)
            .setPriority(data.priority)
            .setLevel(data.level)
            .setRating(data.rating)
            .setVerified(data.verified)
            .setPremium(data.premium)
            .setTier(data.tier)
            .setRegion(data.region)
            .setLanguage(data.language)
            .setCurrency(data.currency)
            .setTimezone(data.timezone)
            // 대량 배열/맵
            .addAllTags(data.tags)
            .addAllPermissions(data.permissions)
            .putAllMetadata(data.metadata)
            .putAllScores(data.scores)
            // 2단계 중첩
            .addAllAddresses(data.addresses.map { buildAddressWithContacts(it) })
            // 4단계 중첩
            .addAllOrders(data.orders.map { buildExtremeOrder(it) })
            .addAllOrganizations(data.organizations.map { buildOrganization(it) })
            // 3단계 중첩
            .addAllEvents(data.events.map { buildEvent(it) })
            .setDescription(data.description)
            .setNotes(data.notes)
            .setAdditionalInfo(data.additionalInfo)
            .build()

        return withResponseSize(
            ComplexDataResponse.newBuilder()
                .setRequestId(requestId)
                .setTimestamp(timestamp)
                .setExtreme(extremeData)
        )
    }

    // ---- Medium/Complex 헬퍼 ----

    private fun buildAddress(addr: AddressDto): Address = Address.newBuilder()
        .setCity(addr.city)
        .setStreet(addr.street)
        .setZipcode(addr.zipcode)
        .setCountry(addr.country)
        .build()

    private fun buildOrder(order: OrderDto): Order = Order.newBuilder()
        .setOrderId(order.orderId)
        .setAmount(order.amount)
        .setTimestamp(order.timestamp)
        .addAllItems(order.items.map { buildItem(it) })
        .build()

    private fun buildItem(item: ItemDto): Item = Item.newBuilder()
        .setProductId(item.productId)
        .setName(item.name)
        .setQuantity(item.quantity)
        .setPrice(item.price)
        .build()

    // ---- Ultra 헬퍼 ----

    private fun buildContact(contact: ContactDto): Contact = Contact.newBuilder()
        .setName(contact.name)
        .setPhone(contact.phone)
        .setEmail(contact.email)
        .setRole(contact.role)
        .build()

    private fun buildAddressWithContacts(addr: AddressWithContactsDto): AddressWithContacts =
        AddressWithContacts.newBuilder()
            .setCity(addr.city)
            .setStreet(addr.street)
            .setZipcode(addr.zipcode)
            .setCountry(addr.country)
            .addAllContacts(addr.contacts.map { buildContact(it) })
            .build()

    private fun buildAttribute(attr: AttributeDto): Attribute = Attribute.newBuilder()
        .setKey(attr.key)
        .setValue(attr.value)
        .setType(attr.type)
        .build()

    private fun buildItemWithAttributes(item: ItemWithAttributesDto): ItemWithAttributes =
        ItemWithAttributes.newBuilder()
            .setProductId(item.productId)
            .setName(item.name)
            .setQuantity(item.quantity)
            .setPrice(item.price)
            .addAllAttributes(item.attributes.map { buildAttribute(it) })
            .build()

    private fun buildOrderWithAttributes(order: OrderWithAttributesDto): OrderWithAttributes =
        OrderWithAttributes.newBuilder()
            .setOrderId(order.orderId)
            .setAmount(order.amount)
            .setTimestamp(order.timestamp)
            .setStatus(order.status)
            .addAllItems(order.items.map { buildItemWithAttributes(it) })
            .build()

    private fun buildSubcategory(subcat: SubcategoryDto): Subcategory = Subcategory.newBuilder()
        .setId(subcat.id)
        .setName(subcat.name)
        .addAllItems(subcat.items)
        .build()

    private fun buildCategory(cat: CategoryDto): Category = Category.newBuilder()
        .setId(cat.id)
        .setName(cat.name)
        .setDescription(cat.description)
        .addAllSubcategories(cat.subcategories.map { buildSubcategory(it) })
        .build()

    private fun buildChange(change: ChangeDto): Change = Change.newBuilder()
        .setField(change.field)
        .setOldValue(change.oldValue)
        .setNewValue(change.newValue)
        .build()

    private fun buildHistoryEntry(entry: HistoryEntryDto): HistoryEntry = HistoryEntry.newBuilder()
        .setId(entry.id)
        .setTimestamp(entry.timestamp)
        .setAction(entry.action)
        .addAllChanges(entry.changes.map { buildChange(it) })
        .build()

    // ---- Extreme 헬퍼 ----

    private fun buildAttributeValue(av: AttributeValueDto): AttributeValue = AttributeValue.newBuilder()
        .setValue(av.value)
        .setLabel(av.label)
        .setIsDefault(av.isDefault)
        .build()

    private fun buildAttributeWithValues(attr: AttributeWithValuesDto): AttributeWithValues =
        AttributeWithValues.newBuilder()
            .setKey(attr.key)
            .setType(attr.type)
            .addAllValues(attr.values.map { buildAttributeValue(it) })
            .build()

    private fun buildExtremeItem(item: ExtremeItemDto): ExtremeItem = ExtremeItem.newBuilder()
        .setProductId(item.productId)
        .setName(item.name)
        .setQuantity(item.quantity)
        .setPrice(item.price)
        .addAllAttributes(item.attributes.map { buildAttributeWithValues(it) })
        .build()

    private fun buildExtremeOrder(order: ExtremeOrderDto): ExtremeOrder = ExtremeOrder.newBuilder()
        .setOrderId(order.orderId)
        .setAmount(order.amount)
        .setTimestamp(order.timestamp)
        .setStatus(order.status)
        .setPriority(order.priority)
        .addAllItems(order.items.map { buildExtremeItem(it) })
        .build()

    private fun buildMember(member: MemberDto): Member = Member.newBuilder()
        .setId(member.id)
        .setName(member.name)
        .setRole(member.role)
        .setEmail(member.email)
        .build()

    private fun buildTeam(team: TeamDto): Team = Team.newBuilder()
        .setId(team.id)
        .setName(team.name)
        .addAllMembers(team.members.map { buildMember(it) })
        .build()

    private fun buildDepartment(dept: DepartmentDto): Department = Department.newBuilder()
        .setId(dept.id)
        .setName(dept.name)
        .setBudget(dept.budget)
        .addAllTeams(dept.teams.map { buildTeam(it) })
        .build()

    private fun buildOrganization(org: OrganizationDto): Organization = Organization.newBuilder()
        .setId(org.id)
        .setName(org.name)
        .setType(org.type)
        .addAllDepartments(org.departments.map { buildDepartment(it) })
        .build()

    private fun buildRole(role: RoleDto): Role = Role.newBuilder()
        .setId(role.id)
        .setName(role.name)
        .addAllPermissions(role.permissions)
        .build()

    private fun buildParticipant(participant: ParticipantDto): Participant = Participant.newBuilder()
        .setId(participant.id)
        .setName(participant.name)
        .setEmail(participant.email)
        .addAllRoles(participant.roles.map { buildRole(it) })
        .build()

    private fun buildEvent(event: EventDto): Event = Event.newBuilder()
        .setId(event.id)
        .setName(event.name)
        .setTimestamp(event.timestamp)
        .setLocation(event.location)
        .addAllParticipants(event.participants.map { buildParticipant(it) })
        .build()
}
