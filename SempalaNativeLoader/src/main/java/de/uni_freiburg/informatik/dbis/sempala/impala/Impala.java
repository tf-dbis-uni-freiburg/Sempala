package de.uni_freiburg.informatik.dbis.sempala.impala;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * This is an Java binding for Impala JDBC.
 * 
 * This class lets you write Impala queries in Java. The statesments that are
 * sent to the impala daemon are build with convenient builders and finally
 * executed. This class is work in progress and is nowhere near final. Things 
 * are getting implemented not until they are needed in the context of sempala.
 * If you miss something implement it!
 * 
 * @author schneidm
 *
 */
public final class Impala {

	/** The connection to the impala daemon */
    private Connection connection = null;
    
    
    
    /** Creates an instance of the impala wrapper. */
    public Impala(String host, String port, String database) throws SQLException{
        // Dynamically load the impala driver // Why is this not necessary?
//		try {
//	    	Class.forName("com.cloudera.impala.jdbc41.Driver");
//		} catch (ClassNotFoundException e) {
//			System.out.println("Where is your JDBC Driver?");
//			e.printStackTrace();
//			return;
//		}
//	    Enumeration<Driver> d = DriverManager.getDrivers();
//		while (d.hasMoreElements()) {
//			System.out.println(d.nextElement());
//		}
    	
    	// Establish the connection to impalad
		String impalad_url = String.format("jdbc:impala://%s:%s/%s", host, port, database);
		System.out.println(String.format("Connecting to impalad (%s)", impalad_url));
		connection = DriverManager.getConnection(impalad_url);
    } 
    
    
    
    @Override
    protected void finalize() throws Throwable {
    	connection.close();
    	connection=null;
    	super.finalize();
    }
    
    
    
    /**
     * Creates a handy builder for the CREATE statement. 
     * 
     * @param tablename The name of the table you want to create.
     * @return The builder for the CREATE statement. 
     */
    public CreateStatement createTable(String tablename){
    	return new CreateStatement(connection, tablename);
    }
    
    
    
    /**
     * Creates a handy builder for the CREATE statement. 
     * 
     * Convenience function for {@link createTable}. This function returns a
     * statement with the external flag initially set.
     *  
     * @param tablename The name of the table you want to create.
     * @return The builder for the CREATE statement. 
     */
    public CreateStatement createTable(){
    	return new CreateStatement(connection);
    }
    
    
    
    /**
     * Creates a handy builder for the INSERT statement. 
     * 
     * @param tablename The name of the table you want to insert into.
     * @return The builder for the INSERT statement. 
     */
    public InsertStatement insertInto(String tablename){
    	return new InsertStatement(connection, tablename);
    }
    
    
    
    /**
     * Creates a handy builder for the INSERT statement. 
     * 
     * Convenience function for {@link insertTable}. This function returns a
     * statement with the overwrite flag initially set.
     *  
     * @param tablename The name of the table you want to create.
     * @return The builder for the INSERT statement. 
     */
    public InsertStatement insertOverwrite(String tablename){
    	return this.insertInto(tablename).overwrite();
    }
    
    
    
    /**
     * Creates a handy builder for the SELECT statement. 
     *  
     * @param tablename The projection expression of your query
     * @return The builder for the SELECT statement. 
     */
    public SelectStatement select(){
    	return new SelectStatement(connection);
    }
    
    
    
    /**
     * Creates a handy builder for the SELECT statement. 
     *  
     * @param tablename The projection expression of your query
     * @return The builder for the SELECT statement. 
     */
    public SelectStatement select(String expression){
    	return new SelectStatement(connection, expression);
    }
    
    
    
    /**
     * Drops a table instantly.
     * 
     * @param tablename The table to drop.
     * @throws SQLException
     */
    public void dropTable(String tablename) throws SQLException {
		System.out.println(String.format("Dropping table '%s'", tablename));
		connection.createStatement().executeUpdate(String.format("DROP TABLE %s;", tablename));
    }
  
    
    
    /**
     * Computes stats for a table (optimization)
     * 
     * Gathers information about volume and distribution of data in a table and
     * all associated columns and partitions. The information is stored in the
     * metastore database, and used by Impala to help optimize queries.
     * 
     * http://www.cloudera.com/content/www/en-us/documentation/enterprise/latest/topics/impala_perf_stats.html?scroll=perf_stats 
     * 
     * @param tablename
     * @throws SQLException
     */
    public void computeStats(String tablename) throws SQLException {
		System.out.println(String.format("Precomputing optimization stats for '%s'", tablename));
		connection.createStatement().executeUpdate(String.format("COMPUTE STATS %s;", tablename));
    }
    

    
    /**
     * Sets an impala query option
     * 
     * http://www.cloudera.com/content/www/en-us/documentation/archive/impala/2-x/2-1-x/topics/impala_query_options.html?scroll=query_options
     * 
     * @param option The option to set.
     * @param value The value of the option to set.
     * @throws SQLException
     */
    public void set(QueryOption option, String value) throws SQLException {
		System.out.println(String.format("Setting impala query option '%s' to '%s'", option.name(), value));
		connection.createStatement().executeUpdate(String.format("SET %s=%s;", option.name(), value));
    }
}
