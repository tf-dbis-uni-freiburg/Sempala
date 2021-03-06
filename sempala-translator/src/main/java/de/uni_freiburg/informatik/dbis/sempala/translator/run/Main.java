package de.uni_freiburg.informatik.dbis.sempala.translator.run;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.log4j.Logger;
import org.apache.spark.sql.DataFrame;

import de.uni_freiburg.informatik.dbis.sempala.translator.ComplexPropertyTableColumns;
import de.uni_freiburg.informatik.dbis.sempala.translator.Format;
import de.uni_freiburg.informatik.dbis.sempala.translator.Tags;
import de.uni_freiburg.informatik.dbis.sempala.translator.Translator;
import de.uni_freiburg.informatik.dbis.sempala.translator.spark.Spark;
import de.uni_freiburg.informatik.dbis.sempala.translator.sql.ExecutionPlatform;
import de.uni_freiburg.informatik.dbis.sempala.translator.sql.ExecutionPlatform.Platform;

/**
 * Main Class for program start. Parses the commandline arguments and calls the
 * Sempala translator.
 *
 * @author Antony Neu, Manuel Schneider
 */
public class Main {

	/** The input file/folder to write to */
	private static String inputPath = null;

	/** The connection to the Impala daemon */
	public static Connection impalaConnection = null;

	/** The object used for connecting to Spark and executing queries. */
	public static Spark sparkConnection = null;

	// Define a static logger variable so that it references the corresponding
	// Logger instance
	private static final Logger logger = Logger.getLogger(Main.class);

	/**
	 * The main routine. It parses the commandline arguments and calls the
	 * Translator.
	 * 
	 * @param args commandline arguments
	 */
	public static void main(String[] args) {

		// Parse command line
		Options options = buildOptions();
		CommandLine commandLine = null;
		CommandLineParser parser = new BasicParser();
		try {
			commandLine = parser.parse(options, args);
		} catch (ParseException e) {
			// Commons CLI is poorly designed and uses exceptions for missing
			// required options. Therefore we can not print help without
			// throwing
			// an exception. We'll just print it on every exception.
			System.err.println(e.getLocalizedMessage());
			HelpFormatter formatter = new HelpFormatter();
			formatter.setWidth(100);
			formatter.printHelp("sempala-translator", options, true);
			System.exit(1);
		}

		String format = commandLine.getOptionValue(OptionNames.FORMAT.toString());
		// when complex property table is queried with spark
		if (format.equals(Format.COMPLEX_PROPERTY_TABLE_SPARK.toString())) {
			// for spark connection only the name of the database is needed
			if (commandLine.hasOption(OptionNames.DATABASE.toString())) {
				String database = commandLine.getOptionValue(OptionNames.DATABASE.toString());
				String partitions = commandLine.getOptionValue(OptionNames.SPARKPARTITIONS.toString());
				sparkConnection = connectToSpark(database, partitions);
				// set that the queries will be executed via Spark platform
				ExecutionPlatform.getInstance(Platform.SPARK);
			} else {
				logger.fatal("Database is required when connecting to Spark.");
				System.exit(1);
			}
			// when Impala is used
		} else {
			/*
			 * If host, port or database is defined, host and database are
			 * required
			 */
			if (commandLine.hasOption(OptionNames.HOST.toString()) || commandLine.hasOption(OptionNames.PORT.toString())
					|| commandLine.hasOption(OptionNames.DATABASE.toString())) {
				if (commandLine.hasOption(OptionNames.HOST.toString())
						&& commandLine.hasOption(OptionNames.DATABASE.toString())) {

					// Build the impalad url
					String host = commandLine.getOptionValue(OptionNames.HOST.toString());
					String port = commandLine.getOptionValue(OptionNames.PORT.toString(), "21050");
					String database = commandLine.getOptionValue(OptionNames.DATABASE.toString());
					String impalad_url = String.format("jdbc:impala://%s:%s/%s", host, port, database);
					impalaConnection = connectToImpala(impalad_url);
					// set that the queries will be executed via Impala platform
					ExecutionPlatform.getInstance(Platform.IMPALA);

				} else {
					logger.fatal("If host, port or database is defined, host and database are required");
					System.exit(1);
				}
			}
		}

		/*
		 * Setup translator
		 */
		Translator translator = new Translator();

		// Enable optimizations if requested
		if (commandLine.hasOption(OptionNames.OPTIMIZE.toString())) {
			translator.setOptimizer(true);
			logger.info("SPARQL Algebra optimization is turned on");
		}

		// Enable prefix expansion if requested
		if (commandLine.hasOption(OptionNames.EXPAND.toString())) {
			translator.setExpandPrefixes(true);
			logger.info("URI prefix expansion is turned on");
		}

		// Set Threshold
		if (commandLine.hasOption(OptionNames.THRESHOLD.toString()))
			translator.setThreshold(commandLine.getOptionValue(OptionNames.THRESHOLD.toString()));
		;

		// Set Join Order
		if (commandLine.hasOption(OptionNames.STRAIGHJOIN.toString()))
			translator.setStraightJoin(true);

		// Set Result Table Name
		if (commandLine.hasOption(OptionNames.RESULT_TABLE_NAME.toString()))
			translator.result_table_name = commandLine.getOptionValue(OptionNames.RESULT_TABLE_NAME.toString());

		// Set requested format
		if (format.equals(Format.PROPERTYTABLE.toString())) {
			translator.setFormat(Format.PROPERTYTABLE);
			logger.info("Format set to propertytable.");
		} else if (format.equals(Format.SINGLETABLE.toString())) {
			translator.setFormat(Format.SINGLETABLE);
			logger.info("Format set to singletable.");
		} else if (format.equals(Format.COMPLEX_PROPERTY_TABLE.toString())) {
			translator.setFormat(Format.COMPLEX_PROPERTY_TABLE);
			logger.info("Format set to complex property table.");
		} else if (format.equals(Format.COMPLEX_PROPERTY_TABLE_SPARK.toString())) {
			translator.setFormat(Format.COMPLEX_PROPERTY_TABLE_SPARK);
			logger.info("Format set to complex property table (Spark).");
		} else if (format.equals(Format.EXTVP.toString())) {
			translator.setFormat(Format.EXTVP);
			logger.info("Format set to ExtVP");
		} else {
			logger.fatal("Fatal: Invalid format specified.");
			System.exit(1);
		}

		// No check, input is required
		inputPath = commandLine.getOptionValue(OptionNames.INPUT.toString());

		/*
		 * Run translator
		 */
		File inputFile = new File(inputPath);
		if (!inputFile.exists()) {
			logger.fatal("Input path does not exist.");
			System.exit(1);
		}

		// Get a list of files that have to be handled
		List<File> inputFiles = new ArrayList<>();
		if (inputFile.isDirectory()) {
			// Run the translator for every file in the folder that matches the
			// common sparql extensions
			for (final File fileEntry : inputFile.listFiles()) {
				if (fileEntry.getName().matches("(.*\\.sq|.*\\.srx|.*\\.sparql|.*\\.in)$")) { // Match
					// only
					// SPARQL
					// extensions
					inputFiles.add(fileEntry);
				}
			}
		} else {
			inputFiles.add(inputFile);
		}

		// if complex_property_table is selected, we need to get the list of
		// properties and their type (simple/complex) as start up phase
		if (impalaConnection != null && format.equals(Format.COMPLEX_PROPERTY_TABLE.toString())) {
			ComplexPropertyTableColumns.getInstance(impalaConnection);
		}
		if (sparkConnection != null && format.equals(Format.COMPLEX_PROPERTY_TABLE_SPARK.toString())) {
			ComplexPropertyTableColumns.getInstance(sparkConnection);
		}

		// check if the running of queries is with benchmark purposes
		boolean isBenchmark = false;
		if (commandLine.hasOption(OptionNames.BENCHMARK.toString())) {
			isBenchmark = true;
		}

		// check if the results should be stored or just counted
		boolean isCount = false;
		if (commandLine.hasOption(OptionNames.COUNT.toString())) {
			isCount = true;
		}

		for (final File file : inputFiles) {

			System.out.print(String.format("%s:", file.getName()));

			// Translate the SPARQL query either for Spark or for Impala
			translator.setInputFile(file.getAbsolutePath());
			String sqlString = translator.translateQuery();
			// Construct the name of the new result table for the current query
			String resultsTableName = constructResultTableName(translator.getFormat(), file.getName(),
					translator.result_table_name);

			HashMap<String, Long> result = null;
			// If a connection is set run the query
			if (impalaConnection != null) {
				// Run the translated query with impala and put it into the
				// unique results table
				result = runQueryWithImpala(impalaConnection, sqlString, resultsTableName, isBenchmark, isCount);
			} else if (sparkConnection != null) {
				// Run the translated query with spark and put it into the
				// unique results table
				result = runQueryWithSpark(sparkConnection, sqlString, resultsTableName, isBenchmark, isCount);
			}
			// if neither impala nor spark connection is initialized
			else {
				// Print resulting SQL script to output file
				PrintWriter printWriter;
				try {
					printWriter = new PrintWriter(file.getAbsolutePath() + ".sql");
					printWriter.print(sqlString);
					printWriter.close();
				} catch (Exception e) {
					logger.warn("Cannot open output file: " + file.getAbsolutePath() + ".sql", e);
					return;
				}
			}

			long executionTime = result.get("executionTime");
			long nrTuples = result.get("nrTuples");
			// store the result for each query in a file
			appendQueryResults("TableOfResults.txt", resultsTableName, executionTime, nrTuples);
		}
		sparkConnection.unpersistCachedTables();
	}

	/**
	 * Create a new file named as the input parameter fileName and store
	 * information for an executed query. If such file exists, only append a new
	 * line for information of the query. For each query we have a line with a
	 * name of the table where the results of the query are saved, its execution
	 * time and how many results does it have.
	 */
	public static void appendQueryResults(String fileName, String resultsTableName, long executionTime, long nrTuples) {
		// store the result for each query in a file
		try (FileWriter fw = new FileWriter("./" + fileName, true);
				BufferedWriter bw = new BufferedWriter(fw);
				PrintWriter Append = new PrintWriter(bw)) {
			Append.println(String.format("%s\t%s\t%s", resultsTableName, executionTime, nrTuples));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Construct the name of a table where the results of a query are stored.
	 * 
	 * @param format the data format supported
	 * @param fileName name of the file from where the query is read
	 * @param translatorResultTableName
	 * @return
	 */
	public static String constructResultTableName(Format format, String fileName, String translatorResultTableName) {
		String resultsTableName = String.format("%s_%d", fileName, System.currentTimeMillis());
		if (format == Format.EXTVP) {
			resultsTableName = String.format("%s_%s_%d", translatorResultTableName, fileName,
					System.currentTimeMillis());
		}
		resultsTableName = resultsTableName.replaceAll("[<>]", "").trim().replaceAll("[[^\\w]+]", "_");
		return resultsTableName;
	}

	/**
	 * Run a query by Impala.
	 * 
	 * @param impalaConnection the connection to Impala which is used for
	 *            running the query
	 * @param sqlQuery sql query in string format that is run
	 * @param resultsTableName name of the table where the result of the query
	 *            is stored.
	 * @param executionTime how much time the query takes
	 * @param nrTuples how many tuples the query has
	 * @param isBenchmark is the query is run with benchmark purposes.
	 */
	public static HashMap<String, Long> runQueryWithImpala(Connection impalaConnection, String sqlQuery,
			String resultsTableName, boolean isBenchmark, boolean isCount) {
		long executionTime = 0;
		long nrTuples = 0;

		try {
			// Sleep a second to give impalad some time to calm down
			Thread.sleep(3000);
			if (isCount) {
				// Execute the query
				long startTime = System.currentTimeMillis();
				ResultSet result = impalaConnection.createStatement()
						.executeQuery(String.format("SELECT COUNT (*) FROM (%s) tabletemp ;", sqlQuery));
				executionTime = System.currentTimeMillis() - startTime;
				System.out.print(String.format(" %s ms", executionTime));

				// Sleep a second to give impalad some time to calm down
				Thread.sleep(3000);
				result.next();

				long tableSize = result.getLong(1);
				nrTuples = tableSize;
				System.out.println(String.format(" %s pc", tableSize));

				// Sleep a second to give impalad some time to calm down
				Thread.sleep(3000);
			} else {

				// Execute the query
				long startTime = System.currentTimeMillis();
				impalaConnection.createStatement().execute(String.format("CREATE TABLE %s.%s AS %s",
						Tags.SEMPALA_RESULTS_DB_NAME, resultsTableName, sqlQuery));
				executionTime = System.currentTimeMillis() - startTime;
				System.out.print(String.format(" %s ms", executionTime));

				// Sleep a second to give impalad some time to calm down
				Thread.sleep(3000);

				// Count the results
				ResultSet result = impalaConnection.createStatement().executeQuery(
						String.format("SELECT COUNT(*) FROM %s.%s;", Tags.SEMPALA_RESULTS_DB_NAME, resultsTableName));
				result.next();
				long tableSize = result.getLong(1);
				nrTuples = tableSize;
				System.out.println(String.format(" %s pc", tableSize));

				// Sleep a second to give impalad some time to calm down
				Thread.sleep(3000);

				// Immediately delete the results if this is just a
				// benchmark run
				if (isBenchmark) {
					impalaConnection.createStatement().executeUpdate(String.format("DROP TABLE IF EXISTS %s.%s;",
							Tags.SEMPALA_RESULTS_DB_NAME, resultsTableName));
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
			System.exit(1);
		} catch (InterruptedException e) {
			e.printStackTrace();
			System.exit(1);
		}

		HashMap<String, Long> results = new HashMap<String, Long>();
		results.put("executionTime", executionTime);
		results.put("nrTuples", nrTuples);
		return results;
	}

	/**
	 * Run a query by Spark.
	 * 
	 * @param sparkConnection connection to spark
	 * @param sqlQuery sql query in string format that is run
	 * @param resultsTableName name of the table where the result of the query
	 *            is stored.
	 * @param executionTime how much time the query takes
	 * @param nrTuples how many tuples the query has
	 * @param isBenchmark is the query is run with benchmark purposes
	 * @param isOnlyCount if true only how many rows the query will returned is
	 *            counted. Otherwise, the result of the query is stored in a
	 *            table
	 */
	public static HashMap<String, Long> runQueryWithSpark(Spark sparkConnection, String sqlQuery,
			String resultsTableName, boolean isBenchmark, boolean isOnlyCount) {
		long executionTime = 0;
		long nrTuples = 0;

		// remove the initial "("
		sqlQuery = sqlQuery.substring(1);
		// remove the final ")"
		sqlQuery = sqlQuery.substring(0, sqlQuery.length() - 1);

		// Execute the query
		long startTime = System.currentTimeMillis();

		// executes query
		DataFrame result = sparkConnection.sql(sqlQuery);
		if (isOnlyCount) {
			nrTuples = result.count();
		} else {
			// store the result into a table
			result.write().saveAsTable(String.format("%s.%s", Tags.SEMPALA_RESULTS_DB_NAME, resultsTableName));
		}

		executionTime = System.currentTimeMillis() - startTime;
		System.out.print(String.format(" %s ms", executionTime));

		// if the result was stored in a table, retrieve its count
		if (!isOnlyCount) {
			nrTuples = result.count();
		}

		System.out.println(String.format(" %s pc", nrTuples));

		// Immediately delete the results if this is just a
		// benchmark run
		if (isBenchmark) {
			sparkConnection
					.sql(String.format("DROP TABLE IF EXISTS %s.%s", Tags.SEMPALA_RESULTS_DB_NAME, resultsTableName));
		}

		HashMap<String, Long> results = new HashMap<String, Long>();
		results.put("executionTime", executionTime);
		results.put("nrTuples", nrTuples);
		return results;
	}

	/**
	 * Connect to Impala and create a database for the tables where the result
	 * of running queries is stored.
	 * 
	 * @param impalaUrl url to Impala
	 * @return initialized Imapala connection
	 */
	public static Connection connectToImpala(String impalaUrl) {
		Connection connection = null;
		try {
			// Connect to the impala server
			System.out.println(String.format("Connecting to impalad (%s)", impalaUrl));
			connection = DriverManager.getConnection(impalaUrl);

			// Create a results database
			connection.createStatement()
					.executeUpdate(String.format("CREATE DATABASE IF NOT EXISTS %s;", Tags.SEMPALA_RESULTS_DB_NAME));

		} catch (SQLException e) {
			logger.fatal(e.getLocalizedMessage());
			System.exit(1);
		}

		return connection;
	}

	/**
	 * Connect to Spark and create a database for result tables. These are the
	 * tables where the result from queries is stored.
	 * 
	 * @param database database over which queries are executed
	 * @param partitions the number of partitions for spark data frame
	 * @return initialized Spark connection
	 */
	public static Spark connectToSpark(String database, String partitions) {
		System.out.println("Connecting to spark");
		// Connect to spark
		Spark sparkConnection = new Spark("sempalaTranslatorApp", database);
		// Create a results database
		sparkConnection.getHiveContext()
				.sql(String.format("CREATE DATABASE IF NOT EXISTS %s", Tags.SEMPALA_RESULTS_DB_NAME));
		
		if (partitions != null) {
			int sp = Integer.parseInt(partitions);
			sparkConnection.setDfPartitions(sp);
		}
		return sparkConnection;

	}

	/**
	 * An enumeration of the options supported by this loader
	 *
	 * -h, --help prints the usage help message -e, --expand expand prefixes
	 * used in the query -opt, --optimize turn on SPARQL algebra optimization
	 * -i, --input <file> SPARQL query file to translate -o, --output <file>
	 * Impala output script file
	 */
	public enum OptionNames {
		BENCHMARK, COUNT, EXPAND, DATABASE, FORMAT, HELP, HOST, INPUT, OPTIMIZE, PORT, RESULT_TABLE_NAME, THRESHOLD, STRAIGHJOIN, SPARKPARTITIONS;

		@Override
		public String toString() {
			return super.toString().toLowerCase();
		}
	}

	/**
	 * Builds the options for this application
	 * 
	 * @return The options collection
	 */
	public static Options buildOptions() {

		Options options = new Options();
		options.addOption("b", OptionNames.BENCHMARK.toString(), false, "Just print runtimes and delete results.");
		options.addOption("c", OptionNames.COUNT.toString(), false, "COUNT result without storing the table.");
		options.addOption("e", OptionNames.EXPAND.toString(), false, "Expand URI prefixes.");
		
		Option databaseOption = new Option("d", OptionNames.DATABASE.toString(), true, "The database to use.");
		databaseOption.setRequired(true);
		options.addOption(databaseOption);
	
		Option formatOption = new Option("f", OptionNames.FORMAT.toString(), true, "The database format the query is built for.\n"
				+ Format.PROPERTYTABLE.toString() + ": (see 'Sempala: Interactive SPARQL Query Processing on Hadoop')\n"
				+ Format.COMPLEX_PROPERTY_TABLE.toString()
				+ ": see Sempala Complex Property Table Master project paper (Impala Version) \n"
				+ Format.COMPLEX_PROPERTY_TABLE_SPARK.toString()
				+ ": see Sempala Complex Property Table Master project paper (Spark Version) \n" + Format.SINGLETABLE.toString()
				+ ": see ExtVP Bigtable, Master's Thesis: S2RDF, Skilevic Simon \n" + Format.EXTVP.toString()
				+ ": see Extended Vertical Partitioning, Master's Thesis: S2RDF, Skilevic Simon\n");
		formatOption.setRequired(true);
		options.addOption(formatOption);
		
		options.addOption("h", OptionNames.HELP.toString(), false, "Print this help.");
		
		options.addOption("H", OptionNames.HOST.toString(), true, "The host to connect to.");
		
		Option inputOption = new Option("i", OptionNames.INPUT.toString(), true,
				"SPARQL query file to translate or folder containing sparql query files.");
		inputOption.setRequired(true);
		options.addOption(inputOption);

		options.addOption("opt", OptionNames.OPTIMIZE.toString(), false, "turn on SPARQL algebra optimization");

		options.addOption("p", OptionNames.PORT.toString(), true, "The port to connect to. (Defaults to 21050)");

		options.addOption("rn", OptionNames.RESULT_TABLE_NAME.toString(), true,
				"Result Table Name format if results tables are stored.");

		options.addOption("s", OptionNames.STRAIGHJOIN.toString(), false,
				"Executes query with Straight join. Default (Impala sets the join order of tables)");

		options.addOption("t", OptionNames.THRESHOLD.toString(), true,
				"Threshold of ExtVP if ExtVP format is selected. Default (SF=1)");

		options.addOption("sp", OptionNames.SPARKPARTITIONS.toString(), true,
				"Number of partitions in Spark. See DataFrame#partitions.");

		return options;
	}
}
