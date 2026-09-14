# Search Response Pagination Envelope Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `GET /search` return `Page<ContentSummaryResponse>` like every other list endpoint, instead of a hand-built `SearchResponse` record that `SuccessResponseWrapper` doesn't auto-unwrap.

**Architecture:** `SearchServiceImpl` already computes a real `Page<Content>` — this is a one-line simplification of its return, plus the matching interface/controller signature changes and deleting the now-unused `SearchResponse` DTO.

**Tech Stack:** Spring Boot, Spring Data JPA, JUnit 5 + Mockito + AssertJ.

**Covers spec:** `docs/superpowers/specs/2026-09-03-search-response-pagination-envelope-design.md`

---

## File Structure

**Modify:**
- `src/main/java/com/tinniestudio/api/modules/search/service/SearchService.java`
- `src/main/java/com/tinniestudio/api/modules/search/service/SearchServiceImpl.java`
- `src/main/java/com/tinniestudio/api/modules/search/controller/SearchController.java`
- `src/test/java/com/tinniestudio/api/modules/search/service/SearchServiceTest.java`
- `src/test/java/com/tinniestudio/api/modules/search/controller/SearchControllerTest.java` (if it exists — confirm at implementation time)

**Delete:**
- `src/main/java/com/tinniestudio/api/modules/search/dto/SearchResponse.java`

---

### Task 1: Change the return type end to end

**Files:**
- Modify: `src/main/java/com/tinniestudio/api/modules/search/service/SearchService.java`
- Modify: `src/main/java/com/tinniestudio/api/modules/search/service/SearchServiceImpl.java`
- Modify: `src/main/java/com/tinniestudio/api/modules/search/controller/SearchController.java`
- Modify: `src/test/java/com/tinniestudio/api/modules/search/service/SearchServiceTest.java`
- Delete: `src/main/java/com/tinniestudio/api/modules/search/dto/SearchResponse.java`

- [ ] **Step 1: Write the failing test**

Find the existing tests in `SearchServiceTest.java` that assert on `SearchResponse`-shaped results (e.g. `result.results()`, `result.total()`, `result.totalPages()` or similar accessors — read the file's current `SearchTests` nested class first to see the exact assertions used today, since this plan's exploration only confirmed the class exists, not its full assertion list). Update each to assert against `Page<ContentSummaryResponse>`'s API instead:

```java
@Test
@DisplayName("returns a Page<ContentSummaryResponse> matching the repository's Page<Content>")
void returnsPageOfContentSummaryResponse() {
    Content movie = publishedMovie();
    Pageable pageable = PageRequest.of(0, 20);
    Page<Content> repoPage = new PageImpl<>(List.of(movie), pageable, 1);

    SearchRequest request = new SearchRequest();
    request.setQ("Interstellar");

    when(contentRepository.searchByRelevance(eq("Interstellar"), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
        .thenReturn(repoPage);

    Page<ContentSummaryResponse> result = searchService.search(request);

    assertThat(result.getContent()).hasSize(1);
    assertThat(result.getContent().get(0).title()).isEqualTo(movie.getTitle());
    assertThat(result.getTotalElements()).isEqualTo(1);
    assertThat(result.getTotalPages()).isEqualTo(1);
}
```

Adjust the mock's argument matchers (`eq`/`isNull`) to match whatever the existing test file's convention already is for the other four `searchByRelevance` parameters (`type`, `language`, `country`, `categorySlug`) — this plan's exploration confirmed the method signature but not every existing test's exact matcher choices for those positions.

- [ ] **Step 2: Run to verify it fails to compile**

Run: `./gradlew :api-service:test --tests SearchServiceTest`
Expected: FAIL to compile — `searchService.search(request)` still returns `SearchResponse`, not `Page<ContentSummaryResponse>`.

- [ ] **Step 3: Update `SearchService` (interface)**

Find:
```java
public interface SearchService {
    SearchResponse search(SearchRequest request);
}
```

Replace with:
```java
public interface SearchService {
    org.springframework.data.domain.Page<com.tinniestudio.api.modules.content.dto.ContentSummaryResponse> search(SearchRequest request);
}
```

(Use proper top-of-file imports rather than fully-qualified names in the final code — written FQN-style here only to make the diff unambiguous; add `import org.springframework.data.domain.Page;` and `import com.tinniestudio.api.modules.content.dto.ContentSummaryResponse;` to the file's imports and reference them by simple name.)

- [ ] **Step 4: Update `SearchServiceImpl`**

Find:
```java
    public SearchResponse search(SearchRequest request) {
        String q = request.getQ() == null ? "" : request.getQ().trim();
        if (q.length() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Search query must be at least 2 characters");
        }

        String typeStr      = request.getType();
        String language     = request.getLanguage();
        String country      = request.getCountry();
        String categorySlug = request.getCategorySlug();
        var pageable        = PageRequest.of(request.getPage(), request.getLimit());

        Page<com.tinniestudio.api.shared.entity.Content> page = switch (request.getSort()) {
            case LATEST  -> contentRepository.searchByLatest(q, typeStr, language, country, categorySlug, pageable);
            case POPULAR -> contentRepository.searchByPopular(q, typeStr, language, country, categorySlug, pageable);
            default      -> contentRepository.searchByRelevance(q, typeStr, language, country, categorySlug, pageable);
        };

        List<ContentSummaryResponse> results = page.map(ContentSummaryResponse::from).toList();

        int totalPages = page.getTotalElements() == 0 ? 0 : page.getTotalPages();

        return new SearchResponse(
            results,
            page.getTotalElements(),
            request.getPage(),
            request.getLimit(),
            totalPages
        );
    }
```

Replace with:
```java
    public Page<ContentSummaryResponse> search(SearchRequest request) {
        String q = request.getQ() == null ? "" : request.getQ().trim();
        if (q.length() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Search query must be at least 2 characters");
        }

        String typeStr      = request.getType();
        String language     = request.getLanguage();
        String country      = request.getCountry();
        String categorySlug = request.getCategorySlug();
        var pageable        = PageRequest.of(request.getPage(), request.getLimit());

        Page<com.tinniestudio.api.shared.entity.Content> page = switch (request.getSort()) {
            case LATEST  -> contentRepository.searchByLatest(q, typeStr, language, country, categorySlug, pageable);
            case POPULAR -> contentRepository.searchByPopular(q, typeStr, language, country, categorySlug, pageable);
            default      -> contentRepository.searchByRelevance(q, typeStr, language, country, categorySlug, pageable);
        };

        return page.map(ContentSummaryResponse::from);
    }
```

Remove the now-unused `import java.util.List;` and `import com.tinniestudio.api.modules.search.dto.SearchResponse;` if nothing else in the file references them.

- [ ] **Step 5: Update `SearchController`**

Find:
```java
import com.tinniestudio.api.modules.search.dto.SearchResponse;
```
```java
    public ResponseEntity<SearchResponse> search(@Valid @ModelAttribute SearchRequest request) {
        return ResponseEntity.ok(searchService.search(request));
    }
```

Replace with (remove the `SearchResponse` import, add `Page`/`ContentSummaryResponse` imports):
```java
import org.springframework.data.domain.Page;
import com.tinniestudio.api.modules.content.dto.ContentSummaryResponse;
```
```java
    public ResponseEntity<Page<ContentSummaryResponse>> search(@Valid @ModelAttribute SearchRequest request) {
        return ResponseEntity.ok(searchService.search(request));
    }
```

- [ ] **Step 6: Delete the now-unused DTO**

```bash
grep -rn "SearchResponse" api-service/src --include="*.java"
```
Expected: no remaining references anywhere (main or test) once Steps 3-5 land. If anything still shows up, fix that call site before deleting — don't delete out from under a live reference.

```bash
rm api-service/src/main/java/com/tinniestudio/api/modules/search/dto/SearchResponse.java
```

- [ ] **Step 7: Run the tests**

Run: `./gradlew :api-service:test --tests SearchServiceTest`
Expected: PASS

- [ ] **Step 8: Check for a controller test**

Run: `find api-service/src/test -iname "SearchControllerTest.java"`

If one exists, read it and update any assertion that depended on the old `{results, total, page, limit, totalPages}` field names — the new envelope (via `SuccessResponseWrapper`) is `{data: [...], meta: {total, page, size, totalPages}}`, so a test asserting `$.data.results` needs to become `$.data` (a plain array) and `$.data.total` needs to become `$.meta.total`, matching however other `Page<T>`-returning controllers' tests already assert this shape (check `ContentControllerTest.java`'s `list()` test for the established convention to mirror).

- [ ] **Step 9: Full module test run**

Run: `./gradlew :api-service:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/search api-service/src/test/java/com/tinniestudio/api/modules/search
git commit -m "feat: /search returns Page<ContentSummaryResponse>, matching every other list endpoint"
```

---

## Self-Review Notes

- **Spec coverage:** single, complete change — return-type unification across interface/impl/controller, dead DTO removed, tests updated to match. Nothing else in the spec to cover.
- **Placeholder scan:** Step 1 and Step 8 both explicitly flag "read the actual current test file first" rather than asserting confidently on content this plan's exploration didn't fully verify — that's intentional honesty about the plan's own limits, not a placeholder to fill in blindly.
