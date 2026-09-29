using System.IO;
using AgvDemo.Bootstrap;
using AgvDemo.Devices.Configuration;
using AgvDemo.Devices.Device;
using AgvDemo.Presentation;
using AgvDemo.Presentation.Hmi;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;

namespace AgvDemo.EditorTools
{
    /// <summary>
    /// 编辑器菜单。场景与设备 Prefab 由编辑器生成，运行时代码不创建任何几何体。
    /// 生成后一切都可以在编辑器里直接调整。
    /// </summary>
    public static class AgvDemoMenu
    {
        const string ScenePath = "Assets/Scenes/AgvDemo.scene";
        const string SettingsPath = "Assets/Settings/DeviceFleetSettings.asset";
        const string PrefabFolder = "Assets/Prefabs/Devices";

        [MenuItem("AgvDemo/一键生成场景与配置")]
        public static void BuildEverything()
        {
            DevicePrefabBuilder.BuildAll();
            var settings = EnsureSettings();
            BuildScene(settings);
            Debug.Log("[AgvDemo] 完成。按 Play 查看设备状态信号流转。");
        }

        [MenuItem("AgvDemo/仅重建设备 Prefab")]
        public static void RebuildPrefabs() => DevicePrefabBuilder.BuildAll();

        [MenuItem("AgvDemo/打开演示场景")]
        public static void OpenScene()
        {
            if (EditorSceneManager.SaveCurrentModifiedScenesIfUserWantsTo())
                EditorSceneManager.OpenScene(ScenePath);
        }

        static void BuildScene(DeviceFleetSettings settings)
        {
            var scene = EditorSceneManager.NewScene(NewSceneSetup.DefaultGameObjects, NewSceneMode.Single);

            var camera = Camera.main;
            if (camera != null)
            {
                camera.transform.position = new Vector3(0f, 16f, -16f);
                camera.transform.rotation = Quaternion.Euler(42f, 0f, 0f);
            }

            // 组合根：整个应用唯一的装配点
            var startup = new GameObject("Startup");
            var scope = startup.AddComponent<ApplicationLifetimeScope>();
            scope.Settings = settings;
            startup.AddComponent<ApplicationController>();
            startup.AddComponent<DeviceFleetHud>();

            // 设备：从 Prefab 实例化，位置与清单一致
            var fleetRoot = new GameObject("Devices");
            for (int i = 0; i < SimulatedFleet.Specs.Count; i++)
            {
                var spec = SimulatedFleet.Specs[i];
                string path = $"{PrefabFolder}/Device_{spec.Descriptor.Id}.prefab";
                var prefab = AssetDatabase.LoadAssetAtPath<GameObject>(path);
                if (prefab == null)
                {
                    Debug.LogWarning($"[AgvDemo] 缺少 Prefab：{path}");
                    continue;
                }

                var instance = (GameObject)PrefabUtility.InstantiatePrefab(prefab);
                instance.transform.SetParent(fleetRoot.transform, false);
                instance.transform.localPosition = new Vector3(spec.HomeX, 0f, -11f);
            }

            // 视图绑定器挂在设备根上，自动收集子节点的 DeviceView
            fleetRoot.AddComponent<DeviceFleetPresenter>();

            Directory.CreateDirectory("Assets/Scenes");
            EditorSceneManager.SaveScene(scene, ScenePath);
            AssetDatabase.SaveAssets();
        }

        static DeviceFleetSettings EnsureSettings()
        {
            var existing = AssetDatabase.LoadAssetAtPath<DeviceFleetSettings>(SettingsPath);
            if (existing != null) return existing;

            Directory.CreateDirectory("Assets/Settings");
            var settings = ScriptableObject.CreateInstance<DeviceFleetSettings>();
            AssetDatabase.CreateAsset(settings, SettingsPath);
            AssetDatabase.SaveAssets();
            return settings;
        }
    }
}
