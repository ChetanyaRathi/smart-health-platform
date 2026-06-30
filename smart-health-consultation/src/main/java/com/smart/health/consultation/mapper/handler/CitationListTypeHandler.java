package com.smart.health.consultation.mapper.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.health.consultation.dto.ConsultStreamResponse;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

/**
 * MyBatis TypeHandler: List&lt;Citation&gt; &lt;-&gt; JSON TEXT
 */
@MappedTypes(List.class)
@MappedJdbcTypes({JdbcType.VARCHAR, JdbcType.LONGVARCHAR, JdbcType.OTHER})
public class CitationListTypeHandler extends BaseTypeHandler<List<ConsultStreamResponse.Citation>> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<ConsultStreamResponse.Citation>> CITATION_LIST_TYPE =
            new TypeReference<>() {};

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i,
                                    List<ConsultStreamResponse.Citation> parameter,
                                    JdbcType jdbcType) throws SQLException {
        try {
            ps.setString(i, OBJECT_MAPPER.writeValueAsString(parameter));
        } catch (JsonProcessingException e) {
            ps.setString(i, null);
        }
    }

    @Override
    public List<ConsultStreamResponse.Citation> getNullableResult(ResultSet rs, String columnName)
            throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public List<ConsultStreamResponse.Citation> getNullableResult(ResultSet rs, int columnIndex)
            throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public List<ConsultStreamResponse.Citation> getNullableResult(CallableStatement cs, int columnIndex)
            throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private List<ConsultStreamResponse.Citation> parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            List<ConsultStreamResponse.Citation> citations = OBJECT_MAPPER.readValue(json, CITATION_LIST_TYPE);
            return citations == null || citations.isEmpty() ? null : citations;
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
