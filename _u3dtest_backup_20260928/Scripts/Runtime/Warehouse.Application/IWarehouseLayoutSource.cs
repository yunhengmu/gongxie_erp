using System.Collections.Generic;
using UnityEngine;

namespace AgvDemo.Warehouse.Application
{
    /// <summary>
    /// 仓库布局的「端口」。定义在消费方（应用层），由基础设施层实现 ——
    /// 这样依赖方向是 Infrastructure → Application，应用层不认识任何具体实现。
    /// 将来换成 HTTP 拉取 yudao 后端，应用层一行都不用改。
    /// </summary>
    public interface IWarehouseLayoutSource
    {
        int GridX { get; }
        int GridZ { get; }
        float CellSize { get; }
        IReadOnlyList<Vector2Int> ShelfCells { get; }
        IReadOnlyList<string> ShelfLabels { get; }
    }
}
