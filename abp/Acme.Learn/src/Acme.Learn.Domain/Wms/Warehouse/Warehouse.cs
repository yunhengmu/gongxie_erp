using System;
using Volo.Abp.Domain.Entities.Auditing;

namespace Acme.Learn.Wms.Warehouse;

public class Warehouse : FullAuditedAggregateRoot<Guid>
{
    public string Code { get; private set; }
    public string Name { get; private set; }
    public bool IsActive { get; private set; }

    protected Warehouse()
    {
        Code = string.Empty;
        Name = string.Empty;
    }

    public Warehouse(Guid id, string code, string name) : base(id)
    {
        Code = code;
        Name = name;
        IsActive = false;           
    }

    public void Rename(string name) => Name = name;

    public void Activate() => IsActive = true;

    public void Deactivate() => IsActive = false;

}
