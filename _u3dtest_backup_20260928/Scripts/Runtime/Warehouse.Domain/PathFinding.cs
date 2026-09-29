using System.Collections.Generic;
using UnityEngine;

namespace AgvDemo.Warehouse.Domain
{
    /// <summary>
    /// 路径规划接口 —— 对应 Spring 里一个可替换的策略 Bean。
    /// </summary>
    public interface IPathFinder
    {
        List<Vector2Int> Find(Vector2Int start, Vector2Int goal);
    }

    /// <summary>
    /// BFS 最短路，只在「行走通道」上搜索（货架格不可通行）。
    /// </summary>
    public sealed class BfsPathFinder : IPathFinder
    {
        readonly int _width;
        readonly int _height;
        readonly bool[] _walkable;

        public BfsPathFinder(int width, int height, IReadOnlyCollection<Vector2Int> blocked)
        {
            _width = width;
            _height = height;
            _walkable = new bool[width * height];
            for (int i = 0; i < _walkable.Length; i++) _walkable[i] = true;
            foreach (var cell in blocked)
            {
                if (InBounds(cell.x, cell.y)) _walkable[cell.y * _width + cell.x] = false;
            }
        }

        public List<Vector2Int> Find(Vector2Int start, Vector2Int goal)
        {
            var result = new List<Vector2Int>();
            if (!InBounds(start.x, start.y) || !InBounds(goal.x, goal.y)) return result;
            if (start == goal) { result.Add(start); return result; }
            if (!_walkable[goal.y * _width + goal.x]) return result;

            var cameFrom = new Dictionary<Vector2Int, Vector2Int>();
            var visited = new bool[_width * _height];
            var queue = new Queue<Vector2Int>();

            visited[start.y * _width + start.x] = true;
            queue.Enqueue(start);

            bool found = false;
            while (queue.Count > 0 && !found)
            {
                var current = queue.Dequeue();
                foreach (var next in Neighbours(current))
                {
                    if (visited[next.y * _width + next.x]) continue;
                    visited[next.y * _width + next.x] = true;
                    cameFrom[next] = current;

                    if (next == goal) { found = true; break; }
                    queue.Enqueue(next);
                }
            }

            if (!found) return result;

            var cursor = goal;
            result.Add(cursor);
            while (cursor != start)
            {
                cursor = cameFrom[cursor];
                result.Add(cursor);
            }
            result.Reverse();
            return result;
        }

        IEnumerable<Vector2Int> Neighbours(Vector2Int cell)
        {
            if (cell.x > 0 && _walkable[cell.y * _width + cell.x - 1]) yield return new Vector2Int(cell.x - 1, cell.y);
            if (cell.x < _width - 1 && _walkable[cell.y * _width + cell.x + 1]) yield return new Vector2Int(cell.x + 1, cell.y);
            if (cell.y > 0 && _walkable[(cell.y - 1) * _width + cell.x]) yield return new Vector2Int(cell.x, cell.y - 1);
            if (cell.y < _height - 1 && _walkable[(cell.y + 1) * _width + cell.x]) yield return new Vector2Int(cell.x, cell.y + 1);
        }

        bool InBounds(int x, int y) => x >= 0 && x < _width && y >= 0 && y < _height;
    }
}
