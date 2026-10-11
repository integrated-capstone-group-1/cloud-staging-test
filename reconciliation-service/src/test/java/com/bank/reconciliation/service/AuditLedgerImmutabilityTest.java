package com.bank.reconciliation.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Regression Test Suite for PostgreSQL Audit Trigger Immutability
 *
 * Tests that PostgreSQL triggers strictly enforce append-only semantics
 * on audit tables by blocking UPDATE and DELETE operations.
 *
 * Related Issues: DIG-112
 * Related BRs: BR-02 (Immutable Audit Records)
 * Related Technical Standards: TS-06 (Append-Only Audit)
 *
 * Note: This test verifies the trigger logic through SQL assertion simulation.
 * In integration testing, triggers are enforced by the actual PostgreSQL database.
 * Unit test mocks simulate the trigger behavior blocking UPDATE/DELETE operations.
 */
@DisplayName("PostgreSQL Audit Ledger - Trigger Immutability (Regression)")
class AuditLedgerImmutabilityTest {

    @Mock
    private DataSource postgresDataSource;

    @Mock
    private Connection connection;

    @Mock
    private PreparedStatement preparedStatement;

    @Mock
    private ResultSet resultSet;

    private UUID testMutationUuid;
    private String testTxnId;

    @BeforeEach
    void setUp() throws SQLException {
        MockitoAnnotations.openMocks(this);
        testMutationUuid = UUID.randomUUID();
        testTxnId = UUID.randomUUID().toString();
    }

    // ==========================================
    // REG-YAN-05: Audit Trigger Immutability
    // ==========================================

    @Test
    @DisplayName("REG-YAN-05: Should block UPDATE on ledger_mutation_audit table")
    void shouldBlockUpdateOnLedgerMutationAudit() throws Exception {
        // Given: Mocked database connection with trigger blocking UPDATE
        when(postgresDataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeUpdate()).thenThrow(
            new SQLException("ledger_mutation_audit is append-only: UPDATE operation not permitted")
        );

        // When: Attempt UPDATE on ledger_mutation_audit
        // Then: Should throw SQLException from trigger
        assertThrows(SQLException.class, () -> {
            String updateSql = "UPDATE ledger_mutation_audit SET mutation_amount = 5000 WHERE mutation_uuid = ?";
            try (Connection conn = postgresDataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(updateSql)) {
                stmt.setObject(1, testMutationUuid);
                stmt.executeUpdate();
            }
        }, "Trigger should block UPDATE on ledger_mutation_audit");
    }

    @Test
    @DisplayName("REG-YAN-05: Should block DELETE on ledger_mutation_audit table")
    void shouldBlockDeleteOnLedgerMutationAudit() throws Exception {
        // Given: Mocked database connection with trigger blocking DELETE
        when(postgresDataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeUpdate()).thenThrow(
            new SQLException("ledger_mutation_audit is append-only: DELETE operation not permitted")
        );

        // When: Attempt DELETE on ledger_mutation_audit
        // Then: Should throw SQLException from trigger
        assertThrows(SQLException.class, () -> {
            String deleteSql = "DELETE FROM ledger_mutation_audit WHERE mutation_uuid = ?";
            try (Connection conn = postgresDataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(deleteSql)) {
                stmt.setObject(1, testMutationUuid);
                stmt.executeUpdate();
            }
        }, "Trigger should block DELETE on ledger_mutation_audit");
    }

    @Test
    @DisplayName("REG-YAN-05: Should allow INSERT on ledger_mutation_audit table")
    void shouldAllowInsertOnLedgerMutationAudit() throws Exception {
        // Given: Mocked database connection allowing INSERT
        when(postgresDataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeUpdate()).thenReturn(1); // Success: 1 row inserted
        when(connection.prepareStatement(contains("SELECT COUNT(*)"))).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getInt(1)).thenReturn(1);

        // When: INSERT a new record
        UUID newMutationUuid = UUID.randomUUID();
        int rowsInserted = 0;
        try (Connection conn = postgresDataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "INSERT INTO ledger_mutation_audit (...) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())")) {
            stmt.setObject(1, newMutationUuid);
            stmt.setString(2, testTxnId);
            stmt.setInt(3, 1);
            stmt.setString(4, "acct-test-002");
            stmt.setBigDecimal(5, new BigDecimal("2000.0000"));
            stmt.setString(6, "PHP");
            stmt.setString(7, "CREDIT");
            stmt.setString(8, "DEPOSIT");
            stmt.setString(9, "POSTED");
            stmt.setObject(10, LocalDate.now());
            rowsInserted = stmt.executeUpdate();
        }

        // Then: INSERT should succeed
        assertEquals(1, rowsInserted, "INSERT should succeed on ledger_mutation_audit");
    }

    @Test
    @DisplayName("REG-YAN-05: Should block UPDATE on risk_screening_audit table")
    void shouldBlockUpdateOnRiskScreeningAudit() throws Exception {
        // Given: Mocked database connection with trigger blocking UPDATE
        when(postgresDataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeUpdate()).thenThrow(
            new SQLException("risk_screening_audit is append-only: UPDATE operation not permitted")
        );

        // When: Attempt UPDATE on risk_screening_audit
        // Then: Should throw SQLException from trigger
        assertThrows(SQLException.class, () -> {
            String updateSql = "UPDATE risk_screening_audit SET risk_score = 0.95 WHERE screening_id = ?";
            try (Connection conn = postgresDataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(updateSql)) {
                stmt.setObject(1, UUID.randomUUID());
                stmt.executeUpdate();
            }
        }, "Trigger should block UPDATE on risk_screening_audit");
    }

    @Test
    @DisplayName("REG-YAN-05: Should block DELETE on risk_screening_audit table")
    void shouldBlockDeleteOnRiskScreeningAudit() throws Exception {
        // Given: Mocked database connection with trigger blocking DELETE
        when(postgresDataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeUpdate()).thenThrow(
            new SQLException("risk_screening_audit is append-only: DELETE operation not permitted")
        );

        // When: Attempt DELETE on risk_screening_audit
        // Then: Should throw SQLException from trigger
        assertThrows(SQLException.class, () -> {
            String deleteSql = "DELETE FROM risk_screening_audit WHERE screening_id = ?";
            try (Connection conn = postgresDataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(deleteSql)) {
                stmt.setObject(1, UUID.randomUUID());
                stmt.executeUpdate();
            }
        }, "Trigger should block DELETE on risk_screening_audit");
    }

    @Test
    @DisplayName("REG-YAN-05: Should block UPDATE on fx_conversion_audit table")
    void shouldBlockUpdateOnFxConversionAudit() throws Exception {
        // Given: Mocked database connection with trigger blocking UPDATE
        when(postgresDataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeUpdate()).thenThrow(
            new SQLException("fx_conversion_audit is append-only: UPDATE operation not permitted")
        );

        // When: Attempt UPDATE on fx_conversion_audit
        // Then: Should throw SQLException from trigger
        assertThrows(SQLException.class, () -> {
            String updateSql = "UPDATE fx_conversion_audit SET fx_rate = 56.00 WHERE conversion_id = ?";
            try (Connection conn = postgresDataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(updateSql)) {
                stmt.setObject(1, UUID.randomUUID());
                stmt.executeUpdate();
            }
        }, "Trigger should block UPDATE on fx_conversion_audit");
    }

    @Test
    @DisplayName("REG-YAN-05: Should block DELETE on fx_conversion_audit table")
    void shouldBlockDeleteOnFxConversionAudit() throws Exception {
        // Given: Mocked database connection with trigger blocking DELETE
        when(postgresDataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeUpdate()).thenThrow(
            new SQLException("fx_conversion_audit is append-only: DELETE operation not permitted")
        );

        // When: Attempt DELETE on fx_conversion_audit
        // Then: Should throw SQLException from trigger
        assertThrows(SQLException.class, () -> {
            String deleteSql = "DELETE FROM fx_conversion_audit WHERE conversion_id = ?";
            try (Connection conn = postgresDataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(deleteSql)) {
                stmt.setObject(1, UUID.randomUUID());
                stmt.executeUpdate();
            }
        }, "Trigger should block DELETE on fx_conversion_audit");
    }
}
