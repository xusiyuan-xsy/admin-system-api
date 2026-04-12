package com.ruoyi.common.jackson;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;

/**
 * Deserializes JSON array, a JSON array string, or a single URL string into {@code List<String>}.
 */
public class JsonStringListDeserializer extends JsonDeserializer<List<String>>
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> LIST_STRING = new TypeReference<List<String>>() {};

    @Override
    public List<String> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException
    {
        JsonToken t = p.currentToken();
        if (t == JsonToken.VALUE_NULL)
        {
            return null;
        }
        if (t == JsonToken.START_ARRAY)
        {
            CollectionType ct = ctxt.getTypeFactory().constructCollectionType(List.class, String.class);
            return ctxt.readValue(p, ct);
        }
        if (t == JsonToken.VALUE_STRING)
        {
            String s = p.getText().trim();
            if (s.isEmpty())
            {
                return null;
            }
            if (s.startsWith("["))
            {
                return MAPPER.readValue(s, LIST_STRING);
            }
            return Collections.singletonList(s);
        }
        return null;
    }
}
