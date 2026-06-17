package com.walterdeane.lore.query

import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import com.walterdeane.lore.model.QueryResponse
import com.walterdeane.lore.model.QueryRequest


@RestController
class QueryController {

    @PostMapping("/query")
    fun query(@RequestBody query: QueryRequest): QueryResponse {
        // Placeholder for actual query processing logic
        return QueryResponse(
            answer = "This is a placeholder answer for the question: ${query.question}",
            sourceDocuments = emptyList() // Placeholder for actual source document retrieval logic
        )
    }

    @PostMapping("/query/search")
    fun query(@RequestBody query: String): QueryResponse {
        // Placeholder for actual query processing logic
        return QueryResponse(
            answer = "This is a placeholder answer for the question: $query",
            sourceDocuments = emptyList() // Placeholder for actual source document retrieval logic
        )
    }

}