using System.Collections.Generic;
using UnityEngine;
using AgvDemo.Core;
using AgvDemo.Warehouse.Application;
using AgvDemo.Warehouse.Domain;
using AgvDemo.Warehouse.Infrastructure;

namespace AgvDemo.Gameplay
{
    /// <summary>
    /// 场景入口 / 表现的「接线层」。职责只有三件：
    ///  1. 搭环境（地面/货架/AGV 的可见对象）
    ///  2. 作为入口装配 Composition Root（调用 DemoHost.Install）
    ///  3. 把「点击」翻译成对应用服务的调用，并驱动 AGV 沿路径行走
    /// 这里不写任何业务规则，也没有一行业务逻辑。
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class AgvDemoBootstrap : MonoBehaviour
    {
        [Header("仓库规模")]
        [Tooltip("横向格子数")]
        public int gridX = 6;
        [Tooltip("纵向格子数")]
        public int gridZ = 5;
        [Tooltip("每格边长（米）")]
        public float cellSize = 2.5f;

        [Header("配色")]
        public Color shelfColor = new Color(0.16f, 0.45f, 0.9f);
        public Color agvColor = new Color(0.95f, 0.6f, 0.15f);

        [Header("运行")]
        [Tooltip("启动后自动执行的取货任务数；0 表示等待手动点击")]
        public int autoTaskCount = 3;
        [Tooltip("每单之间的间隔（秒）")]
        public float autoTaskInterval = 1.5f;

        Transform _root;
        Transform _agv;
        Material _shelfMaterial;
        Material _agvMaterial;
        Material _floorMaterial;
        readonly List<Transform> _shelfTransforms = new List<Transform>();
        readonly List<Vector2Int> _shelfCells = new List<Vector2Int>();
        readonly List<string> _shelfLabels = new List<string>();

        WarehouseAppService _appService;
        Vector2Int _agvCell = new Vector2Int(0, 0);
        List<Vector2Int> _currentPath;
        int _pathIndex;
        string _status = "等待任务";
        int _completedTasks;
        Camera _camera;

        void Awake()
        {
            Application.targetFrameRate = 60;
            _root = new GameObject("Level").transform;
            _root.SetParent(transform.parent, false);

            _camera = Camera.main;
            BuildEnvironment();
            BuildAgv();
            InstallCompositionRoot();

            Debug.Log($"[AgvDemo] 场景就绪：{gridX}×{gridZ} 网格，{_shelfCells.Count} 个货架。点「运行」后点击任意货架即可派单。");
        }

        void OnEnable()
        {
            // 事件解耦：组件之间不互相持有引用，只订阅事件。
            EventBus.Subscribe<AgvTaskAssigned>(OnTaskAssigned);
        }

        void OnDisable()
        {
            EventBus.Unsubscribe<AgvTaskAssigned>(OnTaskAssigned);
        }

        void Start()
        {
            if (autoTaskCount > 0) Invoke(nameof(AutoFirstTask), autoTaskInterval);
        }

        void AutoFirstTask()
        {
            DispatchTo(NearestShelf());
            autoTaskCount--;
            if (autoTaskCount > 0) Invoke(nameof(AutoFirstTask), autoTaskInterval + 2.2f);
        }

        void Update()
        {
            HandleInput();
            FollowPath();
        }

        // ---------- 组合根装配（决定服务实现与依赖关系） ----------

        void InstallCompositionRoot()
        {
            DemoHost.Install(container =>
            {
                // 数据源：现在写死实现；将来换成 HttpWarehouseLayoutSource，这里只改一行。
                container.Instance<IWarehouseLayoutSource>(new HardcodedLayoutSource());

                // 路径算法：接口 + 具体实现，可在不触碰上游的情况下替换。
                container.Register<IPathFinder>(c =>
                {
                    var layout = c.Resolve<IWarehouseLayoutSource>();
                    return new BfsPathFinder(layout.GridX, layout.GridZ, layout.ShelfCells);
                });

                // 应用服务：逻辑编排，依赖全部由容器注入。
                container.Register<WarehouseAppService>(c =>
                    new WarehouseAppService(
                        c.Resolve<IWarehouseLayoutSource>(),
                        c.Resolve<IPathFinder>()));
            });

            _appService = DemoHost.Resolve<WarehouseAppService>();
        }

        // ---------- 环境搭建（纯表现层：建可见对象） ----------

        void BuildEnvironment()
        {
            _floorMaterial = MakeMaterial(new Color(0.22f, 0.23f, 0.26f), 0.05f, 0.2f);
            _shelfMaterial = MakeMaterial(shelfColor, 0.1f, 0.35f);
            _agvMaterial = MakeMaterial(agvColor, 0.2f, 0.6f);

            var floor = GameObject.CreatePrimitive(PrimitiveType.Plane);
            floor.name = "Floor";
            floor.transform.SetParent(_root, false);
            floor.transform.localScale = new Vector3(gridX * cellSize / 10f, 1f, gridZ * cellSize / 10f);
            floor.transform.localPosition = new Vector3(0f, -0.02f, 0f);
            floor.GetComponent<Renderer>().sharedMaterial = _floorMaterial;

            var layout = new HardcodedLayoutSource();
            for (int i = 0; i < layout.ShelfCells.Count; i++)
            {
                CreateShelf(layout.ShelfCells[i], layout.ShelfLabels[i]);
            }
        }

        void CreateShelf(Vector2Int cell, string label)
        {
            var shelf = GameObject.CreatePrimitive(PrimitiveType.Cube);
            shelf.name = $"Shelf_{label}_{cell.x}_{cell.y}";
            shelf.transform.SetParent(_root, false);
            shelf.transform.localPosition = CellToWorld(cell) + new Vector3(0f, 0.75f, 0f);
            shelf.transform.localScale = new Vector3(cellSize * 0.7f, 1.5f, cellSize * 0.7f);
            shelf.GetComponent<Renderer>().sharedMaterial = _shelfMaterial;

            var anchor = new GameObject($"Anchor_{label}");
            anchor.transform.SetParent(shelf.transform, false);
            anchor.transform.localPosition = new Vector3(0f, 0.85f, 0f);

            var pickable = anchor.AddComponent<ShelfPickable>();
            pickable.Init(this, cell, label);

            _shelfTransforms.Add(shelf.transform);
            _shelfCells.Add(cell);
            _shelfLabels.Add(label);
        }

        void BuildAgv()
        {
            _agv = GameObject.CreatePrimitive(PrimitiveType.Cube).transform;
            _agv.name = "AGV";
            _agv.SetParent(_root, false);
            _agv.localScale = new Vector3(cellSize * 0.45f, 0.35f, cellSize * 0.45f);
            _agv.localPosition = CellToWorld(_agvCell) + new Vector3(0f, 0.18f, 0f);
            _agv.GetComponent<Renderer>().sharedMaterial = _agvMaterial;
        }

        // ---------- 输入（表现层只翻译，不决策） ----------

        void HandleInput()
        {
            if (_camera == null) _camera = Camera.main;
            if (_camera == null || !Input.GetMouseButtonDown(0)) return;

            var ray = _camera.ScreenPointToRay(Input.mousePosition);
            if (!Physics.Raycast(ray, out var hit, 200f)) return;

            var pickable = hit.collider.GetComponentInParent<ShelfPickable>();
            if (pickable != null) DispatchTo(pickable.Cell);
        }

        // ---------- 派单 / 行走 ----------

        internal void DispatchTo(Vector2Int shelfCell)
        {
            if (_currentPath != null) return;

            var path = BuildPath(_agvCell, shelfCell);
            if (path == null || path.Count == 0)
            {
                _status = "无可用路径";
                Debug.LogWarning($"[AgvDemo] 到 {LabelOf(shelfCell)} 没有可行路径。");
                return;
            }

            _currentPath = path;
            _pathIndex = 0;
            _status = $"前往 {LabelOf(shelfCell)}（{path.Count - 1} 格）";
            Debug.Log($"[AgvDemo] 派单 -> {LabelOf(shelfCell)}，路径长度 {path.Count - 1} 格。");
        }

        /// <summary>
        /// 这里没有一行业务规则 —— 只是把表现层的请求交给应用服务。
        /// 规则与实现全在 WarehouseAppService / IPathFinder 里，且由 Composition Root 提供。
        /// </summary>
        List<Vector2Int> BuildPath(Vector2Int from, Vector2Int shelfCell)
        {
            if (_appService == null) return new List<Vector2Int>();
            return _appService.PlanPickup(from, shelfCell);
        }

        void FollowPath()
        {
            if (_currentPath == null || _pathIndex >= _currentPath.Count) return;

            Vector3 target = CellToWorld(_currentPath[_pathIndex]);
            Vector3 current = _agv.localPosition;
            Vector3 flatCurrent = new Vector3(current.x, 0f, current.z);
            Vector3 flatTarget = new Vector3(target.x, 0f, target.z);

            float speed = 4.5f;
            float step = speed * Time.deltaTime;
            Vector3 next = Vector3.MoveTowards(flatCurrent, flatTarget, step);
            _agv.localPosition = new Vector3(next.x, current.y, next.z);

            Vector3 look = flatTarget - flatCurrent;
            if (look.sqrMagnitude > 0.0001f)
            {
                var rotation = Quaternion.LookRotation(look, Vector3.up);
                _agv.localRotation = Quaternion.RotateTowards(_agv.localRotation, rotation, 320f * Time.deltaTime);
            }

            if ((new Vector3(next.x, 0f, next.z) - flatTarget).sqrMagnitude < 0.0025f)
            {
                _agvCell = _currentPath[_pathIndex];
                _pathIndex++;

                if (_pathIndex >= _currentPath.Count)
                {
                    _status = $"已到达 {LabelOf(_agvCell)}";
                    _currentPath = null;
                    _completedTasks++;
                    Debug.Log($"[AgvDemo] 第 {_completedTasks} 单完成，停在 {LabelOf(_agvCell)}。");
                }
            }
        }

        // ---------- 事件回调（说明依赖走事件而不是硬引用） ----------

        void OnTaskAssigned(AgvTaskAssigned evt)
        {
            Debug.Log($"[AgvDemo] 事件：任务 #{evt.TaskNo} 已派发，目标 {evt.TargetLabel}。");
        }

        // ---------- 工具 ----------

        Vector3 CellToWorld(Vector2Int cell)
            => GridMath.CellToWorld(cell.x, cell.y, gridZ, cellSize);

        string LabelOf(Vector2Int cell)
        {
            for (int i = 0; i < _shelfCells.Count; i++)
                if (_shelfCells[i] == cell) return _shelfLabels[i];
            return $"({cell.x},{cell.y})";
        }

        Vector2Int NearestShelf()
        {
            Vector2Int best = _shelfCells[0];
            int bestDistance = int.MaxValue;
            foreach (var cell in _shelfCells)
            {
                int d = Mathf.Abs(cell.x - _agvCell.x) + Mathf.Abs(cell.y - _agvCell.y);
                if (d < bestDistance) { bestDistance = d; best = cell; }
            }
            return best;
        }

        static Material MakeMaterial(Color color, float metallic, float smoothness)
        {
            var shader = Shader.Find("Standard");
            var material = new Material(shader);
            material.color = color;
            material.SetFloat("_Metallic", metallic);
            material.SetFloat("_Glossiness", smoothness);
            return material;
        }

        void OnGUI()
        {
            var style = new GUIStyle(GUI.skin.label) { fontSize = 18, fontStyle = FontStyle.Bold };
            var small = new GUIStyle(GUI.skin.label) { fontSize = 14 };

            GUI.Box(new Rect(16, 16, 340, 118), GUIContent.none);
            GUI.Label(new Rect(32, 26, 320, 26), "WCS 调度演示", style);
            GUI.Label(new Rect(32, 56, 320, 22), $"状态：{_status}", small);
            GUI.Label(new Rect(32, 78, 320, 22), $"已完成：{_completedTasks} 单   位置：{LabelOf(_agvCell)}", small);
            GUI.Label(new Rect(32, 100, 320, 22), "鼠标左键点击任意货架派单", small);
        }
    }

    /// <summary>货架上的点击接收器。挂载脚本 = Unity 里给对象绑定交互的一种方式。</summary>
    public sealed class ShelfPickable : MonoBehaviour
    {
        public Vector2Int Cell { get; private set; }
        public string Label { get; private set; }
        AgvDemoBootstrap _owner;

        public void Init(AgvDemoBootstrap owner, Vector2Int cell, string label)
        {
            _owner = owner;
            Cell = cell;
            Label = label;
        }

        void OnMouseDown()
        {
            if (_owner != null) _owner.DispatchTo(Cell);
        }
    }
}