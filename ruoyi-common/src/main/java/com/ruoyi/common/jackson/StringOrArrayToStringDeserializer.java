package com.ruoyi.common.jackson;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

/**
 * Deserializes a JSON string or a JSON array of strings into one {@link String} (arrays joined with comma).
 */
public class StringOrArrayToStringDeserializer extends JsonDeserializer<String>
{
    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException
    {
        JsonToken t = p.currentToken();
        if (t == JsonToken.VALUE_NULL)
        {
            return null;
        }
        if (t == JsonToken.VALUE_STRING || t.isNumeric() || t == JsonToken.VALUE_TRUE || t == JsonToken.VALUE_FALSE)
        {
            return p.getValueAsString();
        }
        if (t == JsonToken.START_ARRAY)
        {
            List<?> list = ctxt.readValue(p, List.class);
            if (list == null || list.isEmpty())
            {
                return null;
            }
            return list.stream()
                    .map(o -> o == null ? "" : o.toString())
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.joining(","));
        }
        return null;
    }
}
