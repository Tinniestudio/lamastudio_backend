package com.tinniestudio.api.shared.config;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test: GenericJackson2JsonRedisSerializer's default ObjectMapper embeds polymorphic
 * type info PROPERTY-style, which has no way to represent a top-level JSON array's type. Any
 * @Cacheable method returning a bare List (categories, content-list, discover, search,
 * recommendations) would serialize fine on cache write, then fail to deserialize the SAME bytes
 * on the very next cache read ("expected VALUE_STRING ... got START_OBJECT/START_ARRAY") —
 * confirmed live against a running instance, deterministic on every write-then-read cycle.
 * RedisConfig.cacheObjectMapper() fixes this with WRAPPER_ARRAY + DefaultTyping.EVERYTHING.
 */
class RedisConfigCacheSerializationTest {

    record SampleDto(UUID id, String name) {}

    @Test
    void listOfDtos_roundTripsThroughSerializeThenDeserialize() {
        GenericJackson2JsonRedisSerializer serializer =
                new GenericJackson2JsonRedisSerializer(RedisConfig.cacheObjectMapper());

        List<SampleDto> original = List.of(
                new SampleDto(UUID.randomUUID(), "Action"),
                new SampleDto(UUID.randomUUID(), "Drama")
        );

        byte[] serialized = serializer.serialize(original);
        Object deserialized = serializer.deserialize(serialized);

        assertThat(deserialized).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<SampleDto> result = (List<SampleDto>) deserialized;
        assertThat(result).containsExactlyElementsOf(original);
    }

    @Test
    void emptyList_roundTrips() {
        GenericJackson2JsonRedisSerializer serializer =
                new GenericJackson2JsonRedisSerializer(RedisConfig.cacheObjectMapper());

        byte[] serialized = serializer.serialize(List.<SampleDto>of());
        Object deserialized = serializer.deserialize(serialized);

        assertThat((List<?>) deserialized).isEmpty();
    }

    @Test
    void singleObject_stillRoundTrips() {
        GenericJackson2JsonRedisSerializer serializer =
                new GenericJackson2JsonRedisSerializer(RedisConfig.cacheObjectMapper());

        SampleDto original = new SampleDto(UUID.randomUUID(), "Comedy");

        byte[] serialized = serializer.serialize(original);
        Object deserialized = serializer.deserialize(serialized);

        assertThat(deserialized).isEqualTo(original);
    }
}
