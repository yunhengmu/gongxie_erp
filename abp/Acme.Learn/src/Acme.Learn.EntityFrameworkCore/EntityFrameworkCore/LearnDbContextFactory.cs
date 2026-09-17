using System;
using System.IO;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Design;
using Microsoft.Extensions.Configuration;

namespace Acme.Learn.EntityFrameworkCore;

/* This class is needed for EF Core console commands
 * (like Add-Migration and Update-Database commands) */
public class LearnDbContextFactory : IDesignTimeDbContextFactory<LearnDbContext>
{
    public LearnDbContext CreateDbContext(string[] args)
    {
        var configuration = BuildConfiguration();
        
        LearnEfCoreEntityExtensionMappings.Configure();

        var builder = new DbContextOptionsBuilder<LearnDbContext>()
            .UseSqlite(configuration.GetConnectionString("Default"));
        
        return new LearnDbContext(builder.Options);
    }

    private static IConfigurationRoot BuildConfiguration()
    {
        var builder = new ConfigurationBuilder()
            .SetBasePath(Path.Combine(Directory.GetCurrentDirectory(), "../Acme.Learn.DbMigrator/"))
            .AddJsonFile("appsettings.json", optional: false)
            .AddEnvironmentVariables();

        return builder.Build();
    }
}
