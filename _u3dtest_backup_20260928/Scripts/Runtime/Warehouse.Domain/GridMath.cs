using UnityEngine;

namespace AgvDemo.Warehouse.Domain
{
    /// <summary>
    /// 场景坐标 ⇄ 仓库网格坐标 的换算。纯函数，不含状态。
    /// </summary>
    public static class GridMath
    {
        public static Vector3 CellToWorld(int x, int z, int gridZ, float cellSize)
        {
            float halfX = 0f;
            float halfZ = (gridZ - 1) * 0.5f;
            return new Vector3((x - halfX) * cellSize, 0f, (z - halfZ) * cellSize);
        }

        /// <summary>(0,0) 在左下角。靠近格子中心视为到达。</summary>
        public static bool IsAtCell(Vector3 world, int x, int z, int gridZ, float cellSize, float tolerance)
        {
            Vector3 center = CellToWorld(x, z, gridZ, cellSize);
            Vector3 delta = world - center;
            delta.y = 0f;
            return delta.sqrMagnitude <= tolerance * tolerance;
        }

        public static Vector2Int WorldToCell(Vector3 world, int gridX, int gridZ, float cellSize)
        {
            float halfZ = (gridZ - 1) * 0.5f;
            int x = Mathf.RoundToInt(world.x / cellSize);
            int z = Mathf.RoundToInt(world.z / cellSize + halfZ);
            return new Vector2Int(Mathf.Clamp(x, 0, gridX - 1), Mathf.Clamp(z, 0, gridZ - 1));
        }
    }
}
