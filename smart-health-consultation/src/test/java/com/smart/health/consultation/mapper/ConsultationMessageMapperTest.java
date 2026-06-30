package com.smart.health.consultation.mapper;

import com.smart.health.consultation.dto.ConsultStreamResponse;
import com.smart.health.consultation.dto.SessionHistoryVO;
import com.smart.health.consultation.entity.ConsultationMessage;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.jdbc.ScriptRunner;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.InputStream;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises ConsultationMessageMapper XML + CitationListTypeHandler against a real JDBC database.
 */
class ConsultationMessageMapperTest {

    private SqlSession sqlSession;
    private ConsultationMessageMapper messageMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:citation_mapper_it;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        runSchema(dataSource);

        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ConsultationMessageMapper.class);

        String resource = "mapper/ConsultationMessageMapper.xml";
        try (InputStream inputStream = Resources.getResourceAsStream(resource)) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(
                    inputStream, configuration, resource, configuration.getSqlFragments()
            ).parse();
        }

        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
        sqlSession = sqlSessionFactory.openSession(true);
        messageMapper = sqlSession.getMapper(ConsultationMessageMapper.class);
    }

    @AfterEach
    void tearDown() {
        if (sqlSession != null) {
            sqlSession.close();
        }
    }

    private void runSchema(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptRunner runner = new ScriptRunner(connection);
            runner.runScript(Resources.getResourceAsReader("sql/test-consultation-message-schema.sql"));
        }
    }

    @Test
    @DisplayName("insert + selectHistory round-trips citations for assistant messages")
    void insertAndSelectHistory_roundTripsCitations() {
        var citations = java.util.List.of(
                ConsultStreamResponse.Citation.builder()
                        .title("高血压诊疗指南")
                        .category("临床指南")
                        .snippet("收缩压 >= 140mmHg")
                        .build()
        );

        ConsultationMessage userMessage = new ConsultationMessage();
        userMessage.setSessionId(1L);
        userMessage.setRole("user");
        userMessage.setContent("我血压偏高怎么办？");
        messageMapper.insert(userMessage);

        ConsultationMessage assistantMessage = new ConsultationMessage();
        assistantMessage.setSessionId(1L);
        assistantMessage.setRole("assistant");
        assistantMessage.setContent("建议低盐饮食并监测血压。");
        assistantMessage.setCitations(citations);
        messageMapper.insert(assistantMessage);

        java.util.List<SessionHistoryVO> history = messageMapper.selectHistoryBySessionId(1L);

        assertThat(history).hasSize(2);

        SessionHistoryVO userHistory = history.get(0);
        assertThat(userHistory.getRole()).isEqualTo("user");
        assertThat(userHistory.getContent()).isEqualTo("我血压偏高怎么办？");
        assertThat(userHistory.getTimestamp()).isNotBlank();
        assertThat(userHistory.getCitations()).isNull();

        SessionHistoryVO assistantHistory = history.get(1);
        assertThat(assistantHistory.getRole()).isEqualTo("assistant");
        assertThat(assistantHistory.getContent()).isEqualTo("建议低盐饮食并监测血压。");
        assertThat(assistantHistory.getTimestamp()).isNotBlank();
        assertThat(assistantHistory.getCitations()).hasSize(1);
        assertThat(assistantHistory.getCitations().get(0).getTitle()).isEqualTo("高血压诊疗指南");
        assertThat(assistantHistory.getCitations().get(0).getCategory()).isEqualTo("临床指南");
        assertThat(assistantHistory.getCitations().get(0).getSnippet()).isEqualTo("收缩压 >= 140mmHg");
    }

    @Test
    @DisplayName("selectHistory returns null citations for legacy rows without JSON")
    void selectHistory_legacyRowsHaveNullCitations() {
        ConsultationMessage assistantMessage = new ConsultationMessage();
        assistantMessage.setSessionId(2L);
        assistantMessage.setRole("assistant");
        assistantMessage.setContent("旧数据无引用");
        assistantMessage.setCitations(null);
        messageMapper.insert(assistantMessage);

        java.util.List<SessionHistoryVO> history = messageMapper.selectHistoryBySessionId(2L);

        assertThat(history).hasSize(1);
        assertThat(history.get(0).getCitations()).isNull();
    }
}
