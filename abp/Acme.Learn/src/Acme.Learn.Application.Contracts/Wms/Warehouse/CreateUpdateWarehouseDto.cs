using System.ComponentModel.DataAnnotations;

namespace Acme.Learn.Wms.Warehouse;

public class CreateUpdateWarehouseDto
{
    [Required]
    [StringLength(32)]
    public string Code { get; set; } = string.Empty;

    [Required]
    [StringLength(128)]
    public string Name { get; set; } = string.Empty;

    [Required]
    public bool IsActive { get; set; }
}
