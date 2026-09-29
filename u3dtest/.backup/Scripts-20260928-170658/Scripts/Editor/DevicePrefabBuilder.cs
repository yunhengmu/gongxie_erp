using System.IO;
using AgvDemo.Devices.Device;
using AgvDemo.Devices.Domain;
using AgvDemo.Presentation;
using UnityEditor;
using UnityEngine;

namespace AgvDemo.EditorTools
{
    /// <summary>
    /// 生成设备 Prefab。每个设备 = 一个 Prefab + 一个 DeviceView 脚本，
    /// 与工业侧通行做法一致（设备即 Prefab）。
    ///
    /// 生成的是**方块搭的占位模型**，之后可在编辑器里直接换成真实模型，
    /// 只要保持 DeviceView 上的部件引用不丢，代码一行都不用改。
    /// </summary>
    public static class DevicePrefabBuilder
    {
        const string PrefabFolder = "Assets/Prefabs/Devices";

        public static void BuildAll()
        {
            Directory.CreateDirectory(PrefabFolder);

            foreach (var spec in SimulatedFleet.Specs)
            {
                var descriptor = spec.Descriptor;
                var prefab = BuildOne(descriptor);
                string path = $"{PrefabFolder}/Device_{descriptor.Id}.prefab";
                PrefabUtility.SaveAsPrefabAsset(prefab, path);
                Object.DestroyImmediate(prefab);
                Debug.Log($"[AgvDemo] 已生成 {path}");
            }

            AssetDatabase.SaveAssets();
            AssetDatabase.Refresh();
        }

        static GameObject BuildOne(DeviceDescriptor descriptor)
        {
            var root = new GameObject($"Device_{descriptor.Id}");

            var anchor = new GameObject("Anchor").transform;
            anchor.SetParent(root.transform, false);

            var view = root.AddComponent<DeviceView>();
            view.EditorSetIdentity(descriptor.Id, descriptor.Kind);

            Transform moving = null;
            Renderer indicator = null;

            switch (descriptor.Kind)
            {
                case DeviceKind.Plc:
                    AddBox(anchor, "Cabinet", new Vector3(0f, 1.1f, 0f), new Vector3(1.1f, 2.2f, 0.8f), new Color(0.30f, 0.33f, 0.38f));
                    indicator = AddBox(anchor, "Lamp", new Vector3(0.35f, 2.0f, -0.44f), new Vector3(0.16f, 0.16f, 0.08f), Color.green).GetComponent<Renderer>();
                    break;

                case DeviceKind.Agv:
                    AddBox(anchor, "Chassis", new Vector3(0f, 0.22f, 0f), new Vector3(1.5f, 0.3f, 1.1f), new Color(0.95f, 0.6f, 0.15f));
                    AddBox(anchor, "Deck", new Vector3(0f, 0.46f, 0f), new Vector3(1.3f, 0.16f, 0.95f), new Color(0.55f, 0.36f, 0.10f));
                    indicator = AddBox(anchor, "Beacon", new Vector3(0f, 0.70f, 0f), new Vector3(0.22f, 0.22f, 0.22f), Color.green).GetComponent<Renderer>();
                    break;

                case DeviceKind.Stacker:
                    AddBox(anchor, "Base", new Vector3(0f, 0.12f, 0f), new Vector3(2.4f, 0.24f, 0.9f), new Color(0.32f, 0.35f, 0.40f));
                    AddBox(anchor, "Column", new Vector3(-0.95f, 2.0f, 0f), new Vector3(0.22f, 4f, 0.5f), new Color(0.40f, 0.44f, 0.50f));
                    moving = AddBox(anchor, "Trolley", new Vector3(0f, 1.0f, 0f), new Vector3(1.2f, 0.35f, 0.8f), new Color(0.88f, 0.72f, 0.20f)).transform;
                    break;

                case DeviceKind.RobotArm:
                    AddCylinder(anchor, "Base", new Vector3(0f, 0.3f, 0f), new Vector3(0.9f, 0.3f, 0.9f), new Color(0.30f, 0.33f, 0.38f));
                    moving = AddBox(anchor, "UpperArm", new Vector3(0f, 1.15f, 0f), new Vector3(0.28f, 1.1f, 0.28f), new Color(0.91f, 0.55f, 0.16f)).transform;
                    indicator = AddBox(anchor, "Tool", new Vector3(0f, 1.85f, 0f), new Vector3(0.2f, 0.2f, 0.2f), Color.green).GetComponent<Renderer>();
                    break;

                case DeviceKind.Cnc:
                    AddBox(anchor, "Body", new Vector3(0f, 0.9f, 0f), new Vector3(2f, 1.8f, 1.6f), new Color(0.26f, 0.29f, 0.33f));
                    moving = AddCylinder(anchor, "Spindle", new Vector3(0f, 0.95f, -0.78f), new Vector3(0.22f, 0.22f, 0.22f), new Color(0.13f, 0.77f, 0.37f)).transform;
                    indicator = AddBox(anchor, "Lamp", new Vector3(0.75f, 1.75f, -0.78f), new Vector3(0.14f, 0.14f, 0.07f), Color.green).GetComponent<Renderer>();
                    break;

                default:
                    AddBox(anchor, "Frame", new Vector3(0f, 1.4f, 0f), new Vector3(0.7f, 0.12f, 0.7f), new Color(0.20f, 0.22f, 0.26f));
                    AddBox(anchor, "Payload", new Vector3(0f, 1.24f, 0f), new Vector3(0.26f, 0.2f, 0.26f), new Color(0.55f, 0.40f, 0.16f));
                    indicator = AddBox(anchor, "Beacon", new Vector3(0f, 1.5f, 0f), new Vector3(0.16f, 0.16f, 0.16f), Color.green).GetComponent<Renderer>();
                    break;
            }

            view.EditorAssign(anchor, moving, indicator);
            return root;
        }

        static GameObject AddBox(Transform parent, string name, Vector3 pos, Vector3 scale, Color color)
        {
            var go = GameObject.CreatePrimitive(PrimitiveType.Cube);
            go.name = name;
            go.transform.SetParent(parent, false);
            go.transform.localPosition = pos;
            go.transform.localScale = scale;
            Paint(go, color);
            return go;
        }

        static GameObject AddCylinder(Transform parent, string name, Vector3 pos, Vector3 scale, Color color)
        {
            var go = GameObject.CreatePrimitive(PrimitiveType.Cylinder);
            go.name = name;
            go.transform.SetParent(parent, false);
            go.transform.localPosition = pos;
            go.transform.localScale = scale;
            Paint(go, color);
            return go;
        }

        static void Paint(GameObject go, Color color)
        {
            var renderer = go.GetComponent<Renderer>();
            var shader = Shader.Find("Standard");
            if (shader != null) renderer.sharedMaterial = new Material(shader) { color = color };
        }
    }
}
