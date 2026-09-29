using System.Collections.Generic;
using UnityEngine;
using AgvDemo.Warehouse.Application;

namespace AgvDemo.Warehouse.Infrastructure
{
    /// <summary>
    /// 写死的布局实现，等价于 Spring 的 @Profile("dev")。
    /// 现场版本会换成 HttpWarehouseLayoutSource（含缓存与失败重试），
    /// 因为它实现的端口在应用层，替换时应用层不需要任何改动。
    /// </summary>
    public sealed class HardcodedLayoutSource : IWarehouseLayoutSource
    {
        public int GridX => 6;
        public int GridZ => 5;
        public float CellSize => 2.5f;

        public IReadOnlyList<Vector2Int> ShelfCells { get; } = new List<Vector2Int>
        {
            new Vector2Int(0, 3), new Vector2Int(2, 3), new Vector2Int(4, 3),
            new Vector2Int(0, 1), new Vector2Int(2, 1), new Vector2Int(4, 1),
        };

        public IReadOnlyList<string> ShelfLabels { get; } = new List<string>
        {
            "A-01", "A-02", "A-03",
            "B-01", "B-02", "B-03",
        };
    }
}
