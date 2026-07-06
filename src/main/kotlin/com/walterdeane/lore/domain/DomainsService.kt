package com.walterdeane.lore.domain

import com.walterdeane.lore.model.Domain
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class DomainsService(private val domainRepository: DomainRepository) {

    fun getDomains(query: String?, pageable: Pageable): Page<Domain> {
        val filtered = domainRepository.findAll(query)
        val start = pageable.offset.toInt().coerceIn(0, filtered.size)
        val end = (start + pageable.pageSize).coerceIn(start, filtered.size)
        return PageImpl(filtered.subList(start, end), pageable, filtered.size.toLong())
    }

    fun getDomainById(id: UUID): Domain? {
        // Placeholder for actual domain retrieval logic
        return domainRepository.findById(id)
    }

    fun updateDomainById(domain: Domain) {
        domainRepository.update(domain)
    }

    fun createDomain(domain: Domain) {
        domainRepository.save(domain)
    }

    fun deleteDomainById(id: UUID) {
        domainRepository.deleteById(id)
    }
}