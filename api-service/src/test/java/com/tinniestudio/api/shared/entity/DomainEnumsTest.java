package com.tinniestudio.api.shared.entity;

import com.tinniestudio.api.shared.entity.DomainEnums.MainCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DomainEnums")
class DomainEnumsTest {

    @Nested
    @DisplayName("MainCategory")
    class MainCategoryTests {

        @Test
        @DisplayName("has exactly 4 fixed values (no Lives category)")
        void hasExactlyFourValues() {
            assertThat(MainCategory.values()).hasSize(4);
        }

        @Test
        @DisplayName("getSlug returns the expected external slug for each value")
        void getSlugReturnsExpectedSlug() {
            assertThat(MainCategory.MOVIES.getSlug()).isEqualTo("movies");
            assertThat(MainCategory.TV_SHOWS.getSlug()).isEqualTo("tv-shows");
            assertThat(MainCategory.KIDS.getSlug()).isEqualTo("kids");
            assertThat(MainCategory.SERMONS.getSlug()).isEqualTo("sermons");
        }

        @Test
        @DisplayName("fromSlug round-trips for every value")
        void fromSlugRoundTripsForEveryValue() {
            for (MainCategory mc : MainCategory.values()) {
                assertThat(MainCategory.fromSlug(mc.getSlug())).isEqualTo(mc);
            }
        }

        @Test
        @DisplayName("slugs are unique across all values")
        void slugsAreUnique() {
            assertThat(MainCategory.values())
                    .extracting(MainCategory::getSlug)
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("fromSlug throws IllegalArgumentException for an unknown slug")
        void fromSlugThrowsForUnknownSlug() {
            assertThatThrownBy(() -> MainCategory.fromSlug("not-a-real-slug"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown mainCategory");
        }
    }
}
