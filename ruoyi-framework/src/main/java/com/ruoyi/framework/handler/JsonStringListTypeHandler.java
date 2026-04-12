package com.ruoyi.framework.handler;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** MySQL JSON column mapped to {@code List<String>} (e.g. photo URLs). */
public class JsonStringListTypeHandler extends BaseTypeHandler<List<String>>
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> LIST_STRING = new TypeReference<List<String>>() {};

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<String> parameter, JdbcType jdbcType) throws SQLException
    {
        try
        {
            ps.setString(i, MAPPER.writeValueAsString(parameter));
        }
        catch (JsonProcessingException e)
        {
            throw new SQLException("Failed to serialize photos JSON", e);
        }
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, String columnName) throws SQLException
    {
        return parse(rs.getString(columnName));
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, int columnIndex) throws SQLException
    {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public List<String> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException
    {
        return parse(cs.getString(columnIndex));
    }

    private static List<String> parse(String json) throws SQLException
    {
        if (json == null || json.isBlank())
        {
            return null;
        }
        try
        {
            return MAPPER.readValue(json, LIST_STRING);
        }
        catch (JsonProcessingException e)
        {
            throw new SQLException("Invalid JSON in photos column", e);
        }
    }
}
