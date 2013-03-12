package com.impossibl.postgres.jdbc;

import static com.impossibl.postgres.jdbc.PSQLErrorUtils.makeSQLException;
import static com.impossibl.postgres.jdbc.PSQLErrorUtils.makeSQLWarningChain;
import static com.impossibl.postgres.jdbc.PSQLTextUtils.getBeginText;
import static com.impossibl.postgres.jdbc.PSQLTextUtils.getCommitText;
import static com.impossibl.postgres.jdbc.PSQLTextUtils.getGetSessionIsolationLevelText;
import static com.impossibl.postgres.jdbc.PSQLTextUtils.getGetSessionReadabilityText;
import static com.impossibl.postgres.jdbc.PSQLTextUtils.getIsolationLevel;
import static com.impossibl.postgres.jdbc.PSQLTextUtils.getProtocolSQLText;
import static com.impossibl.postgres.jdbc.PSQLTextUtils.getReleaseSavepointText;
import static com.impossibl.postgres.jdbc.PSQLTextUtils.getRollbackText;
import static com.impossibl.postgres.jdbc.PSQLTextUtils.getRollbackToText;
import static com.impossibl.postgres.jdbc.PSQLTextUtils.getSetSavepointText;
import static com.impossibl.postgres.jdbc.PSQLTextUtils.getSetSessionIsolationLevelText;
import static com.impossibl.postgres.jdbc.PSQLTextUtils.getSetSessionReadabilityText;
import static com.impossibl.postgres.jdbc.PSQLTextUtils.isTrue;
import static com.impossibl.postgres.protocol.TransactionStatus.Active;
import static com.impossibl.postgres.protocol.TransactionStatus.Idle;
import static java.sql.ResultSet.CLOSE_CURSORS_AT_COMMIT;
import static java.sql.ResultSet.CONCUR_READ_ONLY;
import static java.sql.ResultSet.TYPE_FORWARD_ONLY;
import static java.util.Collections.unmodifiableMap;

import java.io.IOException;
import java.net.SocketAddress;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

import com.impossibl.postgres.protocol.Command;
import com.impossibl.postgres.protocol.PrepareCommand;
import com.impossibl.postgres.system.BasicContext;
import com.impossibl.postgres.types.Type;



public class PSQLConnection extends BasicContext implements Connection {

	long statementId = 0l;
	long portalId = 0l;
	int savepointId;
	private int holdability;
	boolean autoCommit = true;
	boolean readOnly = false;
	int networkTimeout;
	SQLWarning warningChain;
	List<PSQLStatement> activeStatements;

	public PSQLConnection(SocketAddress address, Properties settings, Map<String, Class<?>> targetTypeMap) throws IOException {
		super(address, settings, targetTypeMap);
		activeStatements = new ArrayList<>();
	}

	void checkClosed() throws SQLException {

		if(isClosed())
			throw new SQLException("connection closed");
	}

	void checkManualCommit() throws SQLException {

		if(autoCommit != false)
			throw new SQLException("must not be in auto-commit mode");
	}

	void checkAutoCommit() throws SQLException {

		if(autoCommit != false)
			throw new SQLException("must be in auto-commit mode");
	}

	String getNextStatementName() {
		return String.format("%016X", ++statementId);
	}

	String getNextPortalName() {
		return String.format("%016X", ++portalId);
	}

	void handleStatementClosure(PSQLStatement statement) {

		activeStatements.remove(statement);
	}

	void closeStatements() throws SQLException {

		for(PSQLStatement statement : activeStatements) {

			statement.internalClose();
		}
	}

	@Override
	public boolean isValid(int timeout) throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public Map<String, Class<?>> getTypeMap() throws SQLException {
		checkClosed();
		return unmodifiableMap(targetTypeMap);
	}

	@Override
	public void setTypeMap(Map<String, Class<?>> typeMap) throws SQLException {
		checkClosed();
		targetTypeMap = new HashMap<>(typeMap);
	}

	@Override
	public int getHoldability() throws SQLException {
		checkClosed();
		return holdability;
	}

	@Override
	public void setHoldability(int holdability) throws SQLException {
		checkClosed();
		this.holdability = holdability;
	}

	@Override
	public DatabaseMetaData getMetaData() throws SQLException {
		checkClosed();
		throw new SQLException("not supported");
	}

	/**
	 * Executes the given command and throws a SQLException if an error was
	 * encountered and returns a chain of SQLWarnings if any were generated.
	 * 
	 * @param cmd
	 *          Command to execute
	 * @return Chain of SQLWarning objects if any were encountered
	 * @throws SQLException
	 *           If an error was encountered
	 */
	SQLWarning execute(Command cmd) throws SQLException {

		try {

			protocol.execute(cmd);

			if(cmd.getError() != null) {

				throw makeSQLException(cmd.getError());
			}

			return makeSQLWarningChain(cmd.getWarnings());

		}
		catch(IOException e) {

			throw new SQLException(e);
		}

	}

	/**
	 * Executes the given SQL text ignoring all result values
	 * 
	 * @param sql
	 *          SQL text to execute
	 * @throws SQLException
	 *           If an error was encountered during execution
	 */
	void execute(String sql) throws SQLException {

		try {

			execQuery(sql);

		}
		catch(IOException e) {

			throw new SQLException(e);

		}

	}

	/**
	 * Executes the given SQL text returning the first column of the first row
	 * 
	 * @param sql
	 *          SQL text to execute
	 * @return String String value of the 1st column of the 1st row or empty
	 *         string if no results are available
	 * @throws SQLException
	 *           If an error was encountered during execution
	 */
	String executeForString(String sql) throws SQLException {

		try {

			return execQueryForString(sql);

		}
		catch(IOException e) {

			throw new SQLException(e);

		}

	}

	@Override
	public boolean getAutoCommit() throws SQLException {
		checkClosed();
		return autoCommit;
	}

	@Override
	public void setAutoCommit(boolean autoCommit) throws SQLException {
		checkClosed();

		// Do nothing if no change in state
		if(this.autoCommit == autoCommit)
			return;

		// Commit any in-flight transaction (cannot call commit as it will start a
		// new transaction since we would still be in manual commit mode)
		if(!this.autoCommit && protocol.getTransactionStatus() != Idle) {
			execute(getCommitText());
		}

		this.autoCommit = autoCommit;
	}

	@Override
	public boolean isReadOnly() throws SQLException {
		checkClosed();

		String readability = executeForString(getGetSessionReadabilityText());

		return isTrue(readability);
	}

	@Override
	public void setReadOnly(boolean readOnly) throws SQLException {
		checkClosed();

		if(protocol.getTransactionStatus() != Idle) {
			throw new SQLException("cannot set read only during a transaction");
		}

		execute(getSetSessionReadabilityText(readOnly));
	}

	@Override
	public int getTransactionIsolation() throws SQLException {
		checkClosed();

		String isolLevel = executeForString(getGetSessionIsolationLevelText());

		return getIsolationLevel(isolLevel);
	}

	@Override
	public void setTransactionIsolation(int level) throws SQLException {
		checkClosed();

		if( level != Connection.TRANSACTION_NONE &&
				level != Connection.TRANSACTION_READ_UNCOMMITTED &&
				level != Connection.TRANSACTION_READ_COMMITTED &&
				level != Connection.TRANSACTION_REPEATABLE_READ &&
				level != Connection.TRANSACTION_SERIALIZABLE) {
			throw new SQLException("illegal argument");
		}

		execute(getSetSessionIsolationLevelText(level));
	}

	@Override
	public void commit() throws SQLException {
		checkClosed();
		checkManualCommit();

		// Commit the current transaction
		if(protocol.getTransactionStatus() != Idle) {
			execute(getCommitText());
		}

		// Start new transaction
		execute(getBeginText());
	}

	@Override
	public void rollback() throws SQLException {
		checkClosed();
		checkManualCommit();

		// Roll back the current transaction
		if(protocol.getTransactionStatus() != Idle) {
			execute(getRollbackText());
		}

		// Start new transaction
		execute(getBeginText());
	}

	@Override
	public Savepoint setSavepoint() throws SQLException {
		checkClosed();
		checkManualCommit();

		// Start transaction if none available
		if(protocol.getTransactionStatus() != Active) {
			execute(getBeginText());
		}

		// Allocate new save-point name & wrapper
		PSQLSavepoint savepoint = new PSQLSavepoint(++savepointId);

		// Mark save-point
		execute(getSetSavepointText(savepoint));

		return savepoint;
	}

	@Override
	public Savepoint setSavepoint(String name) throws SQLException {
		checkClosed();
		checkManualCommit();

		// Start transaction if none available
		if(protocol.getTransactionStatus() != Active) {
			execute(getBeginText());
		}

		// Allocate new save-point wrapper
		PSQLSavepoint savepoint = new PSQLSavepoint(name);

		// Mark save-point
		execute(getSetSavepointText(savepoint));

		return savepoint;
	}

	@Override
	public void rollback(Savepoint savepointParam) throws SQLException {
		checkClosed();
		checkManualCommit();

		PSQLSavepoint savepoint = (PSQLSavepoint) savepointParam;

		if(!savepoint.isValid()) {
			throw new SQLException("invalid savepoint");
		}

		// Use up the savepoint
		savepoint.invalidate();

		// Rollback to save-point (if in transaction)
		if(protocol.getTransactionStatus() != Idle) {
			execute(getRollbackToText(savepoint));
		}

	}

	@Override
	public void releaseSavepoint(Savepoint savepointParam) throws SQLException {
		checkClosed();
		checkManualCommit();

		PSQLSavepoint savepoint = (PSQLSavepoint) savepointParam;

		if(!savepoint.isValid()) {
			throw new SQLException("invalid savepoint");
		}

		// Use up the save-point
		savepoint.invalidate();

		// Release the save-point (if in a transaction)
		if(protocol.getTransactionStatus() != Idle) {
			execute(getReleaseSavepointText((PSQLSavepoint) savepoint));
		}

	}

	@Override
	public String getCatalog() throws SQLException {
		checkClosed();
		return null;
	}

	@Override
	public void setCatalog(String catalog) throws SQLException {
		checkClosed();
	}

	@Override
	public String getSchema() throws SQLException {
		checkClosed();
		return null;
	}

	@Override
	public void setSchema(String schema) throws SQLException {
		checkClosed();
	}

	@Override
	public String nativeSQL(String sql) throws SQLException {
		checkClosed();

		return getProtocolSQLText(sql);
	}

	@Override
	public Statement createStatement() throws SQLException {

		return createStatement(TYPE_FORWARD_ONLY, CONCUR_READ_ONLY, CLOSE_CURSORS_AT_COMMIT);
	}

	@Override
	public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {

		return createStatement(resultSetType, resultSetConcurrency, CLOSE_CURSORS_AT_COMMIT);
	}

	@Override
	public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
		checkClosed();
		// TODO: implement
		throw new UnsupportedOperationException();
	}

	@Override
	public PreparedStatement prepareStatement(String sql) throws SQLException {

		return prepareStatement(sql, TYPE_FORWARD_ONLY, CONCUR_READ_ONLY, CLOSE_CURSORS_AT_COMMIT);
	}

	@Override
	public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {

		return prepareStatement(sql, resultSetType, resultSetConcurrency, CLOSE_CURSORS_AT_COMMIT);
	}

	@Override
	public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
		checkClosed();

		sql = nativeSQL(sql);

		String statementName = getNextStatementName();

		PrepareCommand prepare = protocol.createPrepare(statementName, sql, Collections.<Type> emptyList());

		warningChain = execute(prepare);

		PSQLStatement statement = new PSQLStatement(this, statementName, prepare.getDescribedParameterTypes(), prepare.getDescribedResultFields());
		activeStatements.add(statement);
		return statement;
	}

	@Override
	public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
		checkClosed();

		return null;
	}

	@Override
	public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
		checkClosed();
		// TODO: implement
		throw new UnsupportedOperationException();
	}

	@Override
	public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
		checkClosed();
		// TODO: implement
		throw new UnsupportedOperationException();
	}

	@Override
	public CallableStatement prepareCall(String sql) throws SQLException {
		checkClosed();
		// TODO: implement
		throw new UnsupportedOperationException();
	}

	@Override
	public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
		checkClosed();
		// TODO: implement
		throw new UnsupportedOperationException();
	}

	@Override
	public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
		checkClosed();
		// TODO: implement
		throw new UnsupportedOperationException();
	}

	@Override
	public Clob createClob() throws SQLException {
		checkClosed();
		// TODO: implement
		throw new UnsupportedOperationException();
	}

	@Override
	public Blob createBlob() throws SQLException {
		checkClosed();
		// TODO: implement
		throw new UnsupportedOperationException();
	}

	@Override
	public NClob createNClob() throws SQLException {
		checkClosed();
		// TODO: implement
		throw new UnsupportedOperationException();
	}

	@Override
	public SQLXML createSQLXML() throws SQLException {
		checkClosed();
		// TODO: implement
		throw new UnsupportedOperationException();
	}

	@Override
	public void setClientInfo(String name, String value) throws SQLClientInfoException {
		// TODO: implement
		throw new UnsupportedOperationException();
	}

	@Override
	public void setClientInfo(Properties properties) throws SQLClientInfoException {
		// TODO: implement
		throw new UnsupportedOperationException();
	}

	@Override
	public String getClientInfo(String name) throws SQLException {
		checkClosed();
		// TODO: implement
		throw new UnsupportedOperationException();
	}

	@Override
	public Properties getClientInfo() throws SQLException {
		checkClosed();
		// TODO: implement
		throw new UnsupportedOperationException();
	}

	@Override
	public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
		checkClosed();
		// TODO: implement
		throw new UnsupportedOperationException();
	}

	@Override
	public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
		checkClosed();
		// TODO: implement
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean isClosed() throws SQLException {
		return protocol == null;
	}

	@Override
	public void close() throws SQLException {

		// Ignore multiple closes
		if(isClosed())
			return;

		internalClose();
	}

	void internalClose() throws SQLException {

		closeStatements();

		shutdown();
	}

	@Override
	public void abort(Executor executor) throws SQLException {
		// TODO: implement
		throw new UnsupportedOperationException();
	}

	@Override
	public SQLWarning getWarnings() throws SQLException {
		checkClosed();
		// TODO: implement
		throw new UnsupportedOperationException();
	}

	@Override
	public void clearWarnings() throws SQLException {
		checkClosed();
		// TODO: implement
		throw new UnsupportedOperationException();
	}

	@Override
	public int getNetworkTimeout() throws SQLException {
		checkClosed();
		return networkTimeout;
	}

	@Override
	public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
		checkClosed();
		networkTimeout = milliseconds;
	}

	@Override
	public <T> T unwrap(Class<T> iface) throws SQLException {
		return iface.cast(this);
	}

	@Override
	public boolean isWrapperFor(Class<?> iface) throws SQLException {
		return iface.isAssignableFrom(getClass());
	}

}
