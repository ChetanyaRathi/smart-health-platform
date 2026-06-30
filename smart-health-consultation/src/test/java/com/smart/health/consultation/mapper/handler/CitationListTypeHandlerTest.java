package com.smart.health.consultation.mapper.handler;

import com.smart.health.consultation.dto.ConsultStreamResponse;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CitationListTypeHandlerTest {

    private final CitationListTypeHandler typeHandler = new CitationListTypeHandler();

    @Test
    @DisplayName("round-trip serializes and deserializes citations")
    void roundTrip_validCitations() throws Exception {
        List<ConsultStreamResponse.Citation> citations = List.of(
                ConsultStreamResponse.Citation.builder()
                        .title("高血压诊疗指南")
                        .category("临床指南")
                        .snippet("收缩压 >= 140mmHg")
                        .build()
        );

        PreparedStatement ps = mock(PreparedStatement.class);
        typeHandler.setNonNullParameter(ps, 1, citations, JdbcType.VARCHAR);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(ps).setString(org.mockito.ArgumentMatchers.eq(1), jsonCaptor.capture());

        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("citations")).thenReturn(jsonCaptor.getValue());

        List<ConsultStreamResponse.Citation> parsed = typeHandler.getNullableResult(rs, "citations");

        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).getTitle()).isEqualTo("高血压诊疗指南");
        assertThat(parsed.get(0).getCategory()).isEqualTo("临床指南");
        assertThat(parsed.get(0).getSnippet()).isEqualTo("收缩压 >= 140mmHg");
    }

    @Test
    @DisplayName("null JSON returns null")
    void deserialize_nullJson() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("citations")).thenReturn(null);

        assertThat(typeHandler.getNullableResult(rs, "citations")).isNull();
    }

    @Test
    @DisplayName("blank JSON returns null")
    void deserialize_blankJson() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("citations")).thenReturn("   ");

        assertThat(typeHandler.getNullableResult(rs, "citations")).isNull();
    }

    @Test
    @DisplayName("empty JSON array returns null")
    void deserialize_emptyArray() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("citations")).thenReturn("[]");

        assertThat(typeHandler.getNullableResult(rs, "citations")).isNull();
    }

    @Test
    @DisplayName("invalid JSON returns null instead of throwing")
    void deserialize_invalidJson() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("citations")).thenReturn("{not-json");

        assertThat(typeHandler.getNullableResult(rs, "citations")).isNull();
    }

    @Test
    @DisplayName("callable statement column index is supported")
    void deserialize_callableStatement() throws Exception {
        CallableStatement cs = mock(CallableStatement.class);
        when(cs.getString(2)).thenReturn("[{\"title\":\"A\",\"category\":\"B\",\"snippet\":\"C\"}]");

        List<ConsultStreamResponse.Citation> parsed = typeHandler.getNullableResult(cs, 2);

        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).getTitle()).isEqualTo("A");
    }

    @Test
    @DisplayName("empty list parameter serializes to JSON array")
    void serialize_emptyList() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        typeHandler.setNonNullParameter(ps, 1, Collections.emptyList(), JdbcType.VARCHAR);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(ps).setString(org.mockito.ArgumentMatchers.eq(1), jsonCaptor.capture());
        assertThat(jsonCaptor.getValue()).isEqualTo("[]");
    }
}
