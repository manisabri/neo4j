/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.importer;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.neo4j.batchinsert.internal.TransactionLogsInitializer;
import org.neo4j.commandline.admin.OutsideWorld;
import org.neo4j.commandline.admin.RealOutsideWorld;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.csv.reader.IllegalMultilineFieldException;
import org.neo4j.internal.batchimport.BatchImporter;
import org.neo4j.internal.batchimport.BatchImporterFactory;
import org.neo4j.internal.batchimport.Configuration;
import org.neo4j.internal.batchimport.cache.idmapping.string.DuplicateInputIdException;
import org.neo4j.internal.batchimport.input.BadCollector;
import org.neo4j.internal.batchimport.input.Collector;
import org.neo4j.internal.batchimport.input.IdType;
import org.neo4j.internal.batchimport.input.Input;
import org.neo4j.internal.batchimport.input.InputException;
import org.neo4j.internal.batchimport.input.MissingRelationshipDataException;
import org.neo4j.internal.batchimport.input.csv.CsvInput;
import org.neo4j.internal.batchimport.input.csv.DataFactory;
import org.neo4j.internal.batchimport.staging.ExecutionMonitor;
import org.neo4j.internal.batchimport.staging.ExecutionMonitors;
import org.neo4j.internal.batchimport.staging.SpectrumExecutionMonitor;
import org.neo4j.internal.helpers.Exceptions;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.layout.DatabaseLayout;
import org.neo4j.io.os.OsBeanUtil;
import org.neo4j.kernel.impl.store.format.RecordFormatSelector;
import org.neo4j.kernel.internal.Version;
import org.neo4j.kernel.lifecycle.LifeSupport;
import org.neo4j.logging.internal.LogService;
import org.neo4j.logging.internal.StoreLogService;
import org.neo4j.scheduler.JobScheduler;

import static java.util.Objects.requireNonNull;
import static org.neo4j.configuration.GraphDatabaseSettings.store_internal_log_path;
import static org.neo4j.importer.ImportCommand.DEFAULT_REPORT_FILE_NAME;
import static org.neo4j.importer.ImportCommand.OPT_MULTILINE_FIELDS;
import static org.neo4j.internal.batchimport.AdditionalInitialIds.EMPTY;
import static org.neo4j.internal.batchimport.input.Collectors.badCollector;
import static org.neo4j.internal.batchimport.input.Collectors.collect;
import static org.neo4j.internal.batchimport.input.Collectors.silentBadCollector;
import static org.neo4j.internal.batchimport.input.InputEntityDecorators.NO_DECORATOR;
import static org.neo4j.internal.batchimport.input.InputEntityDecorators.additiveLabels;
import static org.neo4j.internal.batchimport.input.InputEntityDecorators.defaultRelationshipType;
import static org.neo4j.internal.batchimport.input.csv.DataFactories.data;
import static org.neo4j.internal.batchimport.input.csv.DataFactories.defaultFormatNodeFileHeader;
import static org.neo4j.internal.batchimport.input.csv.DataFactories.defaultFormatRelationshipFileHeader;
import static org.neo4j.internal.helpers.Exceptions.throwIfUnchecked;
import static org.neo4j.io.ByteUnit.bytesToString;
import static org.neo4j.kernel.impl.scheduler.JobSchedulerFactory.createScheduler;

class CsvImporter implements Importer
{
    /**
     * Delimiter used between files in an input group.
     */
    static final String MULTI_FILE_DELIMITER = ",";

    private final DatabaseLayout databaseLayout;
    private final Config databaseConfig;
    private final org.neo4j.csv.reader.Configuration csvConfig;
    private final org.neo4j.internal.batchimport.Configuration importConfig;
    private final File reportFile;
    private final IdType idType;
    private final Charset inputEncoding;
    private final OutsideWorld outsideWorld;
    private final boolean ignoreExtraColumns;
    private final boolean skipBadRelationships;
    private final boolean skipDuplicateNodes;
    private final boolean skipBadEntriesLogging;
    private final long badTolerance;
    private final boolean normalizeTypes;
    private final boolean verbose;
    private final Map<Set<String>, List<File[]>> nodeFiles;
    private final Map<String, List<File[]>> relationshipFiles;

    private CsvImporter( Builder b )
    {
        this.databaseLayout = requireNonNull( b.databaseLayout );
        this.databaseConfig = requireNonNull( b.databaseConfig );
        this.csvConfig = requireNonNull( b.csvConfig );
        this.importConfig = requireNonNull( b.importConfig );
        this.reportFile = requireNonNull( b.reportFile );
        this.idType = requireNonNull( b.idType );
        this.inputEncoding = requireNonNull( b.inputEncoding );
        this.outsideWorld = requireNonNull( b.outsideWorld );
        this.ignoreExtraColumns = b.ignoreExtraColumns;
        this.skipBadRelationships = b.skipBadRelationships;
        this.skipDuplicateNodes = b.skipDuplicateNodes;
        this.skipBadEntriesLogging = b.skipBadEntriesLogging;
        this.badTolerance = b.badTolerance;
        this.normalizeTypes = b.normalizeTypes;
        this.verbose = b.verbose;
        this.nodeFiles = requireNonNull( b.nodeFiles );
        this.relationshipFiles = requireNonNull( b.relationshipFiles );

    }

    @Override
    public void doImport() throws IOException
    {
        FileSystemAbstraction fs = outsideWorld.fileSystem();

        OutputStream badOutput = new BufferedOutputStream( fs.openAsOutputStream( reportFile, false ) );
        try ( Collector badCollector = getBadCollector( skipBadEntriesLogging, badOutput ) )
        {
            // Extract the default time zone from the database configuration
            ZoneId dbTimeZone = databaseConfig.get( GraphDatabaseSettings.db_temporal_timezone );
            Supplier<ZoneId> defaultTimeZone = () -> dbTimeZone;

            final var nodeData = nodeData();
            final var relationshipsData = relationshipData();

            CsvInput input = new CsvInput( nodeData, defaultFormatNodeFileHeader( defaultTimeZone, normalizeTypes ),
                relationshipsData, defaultFormatRelationshipFileHeader( defaultTimeZone, normalizeTypes ), idType,
                csvConfig,
                    new CsvInput.PrintingMonitor( outsideWorld.outStream() ) );

            doImport( input, badCollector );
        }
    }

    private void doImport( Input input, Collector badCollector ) throws IOException
    {
        boolean success;
        LifeSupport life = new LifeSupport();

        File internalLogFile = databaseConfig.get( store_internal_log_path );
        LogService logService = life.add( StoreLogService.withInternalLog( internalLogFile ).build( outsideWorld.fileSystem() ) );
        final JobScheduler jobScheduler = life.add( createScheduler() );

        life.start();
        ExecutionMonitor executionMonitor = verbose ? new SpectrumExecutionMonitor( 2, TimeUnit.SECONDS, outsideWorld.outStream(),
            SpectrumExecutionMonitor.DEFAULT_WIDTH ) : ExecutionMonitors.defaultVisible( outsideWorld.inStream(), jobScheduler );
        BatchImporter importer = BatchImporterFactory.withHighestPriority().instantiate( databaseLayout,
            outsideWorld.fileSystem(),
            null, // no external page cache
            importConfig,
            logService, executionMonitor,
            EMPTY,
            databaseConfig,
            RecordFormatSelector.selectForConfig( databaseConfig, logService.getInternalLogProvider() ),
            new PrintingImportLogicMonitor( outsideWorld.outStream(), outsideWorld.errorStream() ),
            jobScheduler, badCollector, TransactionLogsInitializer.INSTANCE );
        printOverview( databaseLayout.databaseDirectory(), nodeFiles, relationshipFiles, importConfig, outsideWorld.outStream() );
        success = false;
        try
        {
            importer.doImport( input );
            success = true;
        }
        catch ( Exception e )
        {
            throw andPrintError( "Import error", e, verbose, outsideWorld.errorStream() );
        }
        finally
        {
            long numberOfBadEntries = badCollector.badEntries();

            if ( reportFile != null )
            {
                if ( numberOfBadEntries > 0 )
                {
                    outsideWorld.outStream().println( "There were bad entries which were skipped and logged into " + reportFile.getAbsolutePath() );
                }
            }

            life.shutdown();

            if ( !success )
            {
                outsideWorld.errorStream().println( "WARNING Import failed. The store files in " + databaseLayout.databaseDirectory().getAbsolutePath() +
                        " are left as they are, although they are likely in an unusable state. " +
                        "Starting a database on these store files will likely fail or observe inconsistent records so " +
                        "start at your own risk or delete the store manually" );
            }
        }
    }

    /**
     * Method name looks strange, but look at how it's used and you'll see why it's named like that.
     *
     * @param stackTrace whether or not to also print the stack trace of the error.
     */
    private static RuntimeException andPrintError( String typeOfError, Exception e, boolean stackTrace,
            PrintStream err )
    {
        // List of common errors that can be explained to the user
        if ( DuplicateInputIdException.class.equals( e.getClass() ) )
        {
            printErrorMessage( "Duplicate input ids that would otherwise clash can be put into separate id space.", e, stackTrace, err );
        }
        else if ( MissingRelationshipDataException.class.equals( e.getClass() ) )
        {
            printErrorMessage( "Relationship missing mandatory field", e, stackTrace, err );
        }
        // This type of exception is wrapped since our input code throws InputException consistently,
        // and so IllegalMultilineFieldException comes from the csv component, which has no access to InputException
        // therefore it's wrapped.
        else if ( Exceptions.contains( e, IllegalMultilineFieldException.class ) )
        {
            printErrorMessage( "Detected field which spanned multiple lines for an import where " +
                    OPT_MULTILINE_FIELDS + "=false. If you know that your input data " +
                    "include fields containing new-line characters then import with this option set to " +
                    "true.", e, stackTrace, err );
        }
        else if ( Exceptions.contains( e, InputException.class ) )
        {
            printErrorMessage( "Error in input data", e, stackTrace, err );
        }
        // Fallback to printing generic error and stack trace
        else
        {
            printErrorMessage( typeOfError + ": " + e.getMessage(), e, true, err );
        }
        err.println();

        // Mute the stack trace that the default exception handler would have liked to print.
        // Calling System.exit( 1 ) or similar would be convenient on one hand since we can set
        // a specific exit code. On the other hand It's very inconvenient to have any System.exit
        // call in code that is tested.
        Thread.currentThread().setUncaughtExceptionHandler( ( t, e1 ) ->
        {
            /* Shhhh */
        } );
        throwIfUnchecked( e );
        return new RuntimeException( e ); // throw in order to have process exit with !0
    }

    private static void printErrorMessage( String string, Exception e, boolean stackTrace, PrintStream err )
    {
        err.println( string );
        err.println( "Caused by:" + e.getMessage() );
        if ( stackTrace )
        {
            e.printStackTrace( err );
        }
    }

    private static void printOverview( File storeDir, Map<Set<String>, List<File[]>> nodesFiles, Map<String, List<File[]>> relationshipsFiles,
        Configuration configuration, PrintStream out )
    {
        out.println( "Neo4j version: " + Version.getNeo4jVersion() );
        out.println( "Importing the contents of these files into " + storeDir + ":" );
        printInputFiles( "Nodes", nodesFiles, out );
        printInputFiles( "Relationships", relationshipsFiles, out );
        out.println();
        out.println( "Available resources:" );
        printIndented( "Total machine memory: " + bytesToString( OsBeanUtil.getTotalPhysicalMemory() ), out );
        printIndented( "Free machine memory: " + bytesToString( OsBeanUtil.getFreePhysicalMemory() ), out );
        printIndented( "Max heap memory : " + bytesToString( Runtime.getRuntime().maxMemory() ), out );
        printIndented( "Processors: " + configuration.maxNumberOfProcessors(), out );
        printIndented( "Configured max memory: " + bytesToString( configuration.maxMemoryUsage() ), out );
        printIndented( "High-IO: " + configuration.highIO(), out );
        out.println();
    }

    private static void printInputFiles( String name, Map<?, List<File[]>> inputFiles, PrintStream out )
    {
        if ( inputFiles.isEmpty() )
        {
            return;
        }

        out.println( name + ":" );
        int i = 0;

        inputFiles.forEach( ( k, files ) ->
        {
            printIndented( k + ":", out );
            for ( File[] arr : files )
            {
                for ( final File file : arr )
                {
                    printIndented( file, out );
                }
            }
            out.println();
        } );
    }

    private static void printIndented( Object value, PrintStream out )
    {
        out.println( "  " + value );
    }

    private Iterable<DataFactory> relationshipData()
    {
        final var result = new ArrayList<DataFactory>();
        relationshipFiles.forEach( ( defaultTypeName, fileSets ) ->
        {
            final var decorator = defaultRelationshipType( defaultTypeName );
            for ( File[] files : fileSets )
            {
                final var data = data( decorator, inputEncoding, files );
                result.add( data );
            }
        } );
        return result;
    }

    private Iterable<DataFactory> nodeData()
    {
        final var result = new ArrayList<DataFactory>();
        nodeFiles.forEach( ( labels, fileSets ) ->
        {
            final var decorator = labels.isEmpty() ? NO_DECORATOR : additiveLabels( labels.toArray( new String[0] ) );
            for ( File[] files : fileSets )
            {
                final var data = data( decorator, inputEncoding, files );
                result.add( data );
            }
        } );
        return result;
    }

    private Collector getBadCollector( boolean skipBadEntriesLogging, OutputStream badOutput )
    {
        return skipBadEntriesLogging ? silentBadCollector( badTolerance ) :
               badCollector( badOutput, isIgnoringSomething() ? BadCollector.UNLIMITED_TOLERANCE : 0,
                       collect( skipBadRelationships, skipDuplicateNodes, ignoreExtraColumns ) );
    }

    private boolean isIgnoringSomething()
    {
        return skipBadRelationships || skipDuplicateNodes || ignoreExtraColumns;
    }

    static Builder builder()
    {
        return new Builder();
    }

    static class Builder
    {
        private DatabaseLayout databaseLayout;
        private Config databaseConfig;
        private org.neo4j.csv.reader.Configuration csvConfig = org.neo4j.csv.reader.Configuration.COMMAS;
        private Configuration importConfig = Configuration.DEFAULT;
        private File reportFile = new File( DEFAULT_REPORT_FILE_NAME );
        private IdType idType = IdType.STRING;
        private Charset inputEncoding = StandardCharsets.UTF_8;
        private OutsideWorld outsideWorld = new RealOutsideWorld();
        private boolean ignoreExtraColumns;
        private boolean skipBadRelationships;
        private boolean skipDuplicateNodes;
        private boolean skipBadEntriesLogging;
        private long badTolerance;
        private boolean normalizeTypes;
        private boolean verbose;
        private Map<Set<String>, List<File[]>> nodeFiles = new HashMap<>();
        private Map<String, List<File[]>> relationshipFiles = new HashMap<>();

        Builder withDatabaseLayout( DatabaseLayout databaseLayout )
        {
            this.databaseLayout = databaseLayout;
            return this;
        }

        Builder withDatabaseConfig( Config databaseConfig )
        {
            this.databaseConfig = databaseConfig;
            return this;
        }

        Builder withCsvConfig( org.neo4j.csv.reader.Configuration csvConfig )
        {
            this.csvConfig = csvConfig;
            return this;
        }

        Builder withImportConfig( Configuration importConfig )
        {
            this.importConfig = importConfig;
            return this;
        }

        Builder withReportFile( File reportFile )
        {
            this.reportFile = reportFile;
            return this;
        }

        Builder withIdType( IdType idType )
        {
            this.idType = idType;
            return this;
        }

        Builder withInputEncoding( Charset inputEncoding )
        {
            this.inputEncoding = inputEncoding;
            return this;
        }

        Builder withOutsideWorld( OutsideWorld outsideWorld )
        {
            this.outsideWorld = outsideWorld;
            return this;
        }

        Builder withIgnoreExtraColumns( boolean ignoreExtraColumns )
        {
            this.ignoreExtraColumns = ignoreExtraColumns;
            return this;
        }

        Builder withSkipBadRelationships( boolean skipBadRelationships )
        {
            this.skipBadRelationships = skipBadRelationships;
            return this;
        }

        Builder withSkipDuplicateNodes( boolean skipDuplicateNodes )
        {
            this.skipDuplicateNodes = skipDuplicateNodes;
            return this;
        }

        Builder withSkipBadEntriesLogging( boolean skipBadEntriesLogging )
        {
            this.skipBadEntriesLogging = skipBadEntriesLogging;
            return this;
        }

        Builder withBadTolerance( long badTolerance )
        {
            this.badTolerance = badTolerance;
            return this;
        }

        Builder withNormalizeTypes( boolean normalizeTypes )
        {
            this.normalizeTypes = normalizeTypes;
            return this;
        }

        Builder withVerbose( boolean verbose )
        {
            this.verbose = verbose;
            return this;
        }

        Builder addNodeFiles( Set<String> labels, File[] files )
        {
            final var list = nodeFiles.computeIfAbsent( labels, unused -> new ArrayList<>() );
            list.add( files );
            return this;
        }

        Builder addRelationshipFiles( String defaultRelType, File[] files )
        {
            final var list = relationshipFiles.computeIfAbsent( defaultRelType, unused -> new ArrayList<>() );
            list.add( files );
            return this;
        }

        CsvImporter build()
        {
            return new CsvImporter( this );
        }
    }
}