package org.enso.microsoft;

import org.enso.database.DatabaseConnectionDetailsSPI;

@org.openide.util.lookup.ServiceProvider(service = DatabaseConnectionDetailsSPI.class)
public class SQLServerConnectionDetailsSPI extends DatabaseConnectionDetailsSPI {

    @Override
    protected String getModuleName() {
        return "Standard.Mircosoft.SQLServer_Details";
    }

    @Override
    protected String getTypeName() {
        return "SQLServer_Details";
    }

    @Override
    protected String getCodeForDefaultConstructor() {
        return "(SQLServer_Details.SQLServer)";
    }

    @Override
    protected String getUserFacingConnectionName() {
        return "Microsoft SQL Server";
    }
}
