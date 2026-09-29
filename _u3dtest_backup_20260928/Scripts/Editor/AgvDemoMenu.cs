using AgvDemo.Gameplay;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;

namespace AgvDemo.EditorTools
{
    /// <summary>
    /// 编辑器菜单：场景已经写好脚本引用，这里只做便捷入口与自检。
    /// </summary>
    public static class AgvDemoMenu
    {
        const string ScenePath = "Assets/Scenes/AgvDemo.scene";

        [MenuItem("AgvDemo/打开演示场景 %#o")]
        public static void OpenDemoScene()
        {
            if (EditorSceneManager.SaveCurrentModifiedScenesIfUserWantsTo())
                EditorSceneManager.OpenScene(ScenePath);
        }

        [MenuItem("AgvDemo/开始运行 _F5")]
        public static void Play()
        {
            if (!EditorApplication.isPlaying) EditorApplication.isPlaying = true;
        }

        [MenuItem("AgvDemo/开始运行 _F5", true)]
        public static bool PlayValidate() => !EditorApplication.isPlaying;

        [MenuItem("AgvDemo/停止运行 _F5")]
        public static void Stop()
        {
            if (EditorApplication.isPlaying) EditorApplication.isPlaying = false;
        }

        [MenuItem("AgvDemo/停止运行 _F5", true)]
        public static bool StopValidate() => EditorApplication.isPlaying;

        [MenuItem("AgvDemo/生成场景骨架")]
        public static void CreateFallbackScene()
        {
            var scene = EditorSceneManager.NewScene(NewSceneSetup.DefaultGameObjects, NewSceneMode.Single);

            var cam = Camera.main;
            if (cam != null)
            {
                cam.transform.position = new Vector3(0f, 14f, -13f);
                cam.transform.rotation = Quaternion.Euler(46f, 0f, 0f);
            }

            var demo = new GameObject("AgvDemo");
            demo.AddComponent<AgvDemoBootstrap>();

            System.IO.Directory.CreateDirectory("Assets/Scenes");
            EditorSceneManager.SaveScene(scene, ScenePath);
            Debug.Log($"[AgvDemo] 已生成场景 {ScenePath}，按 Play 即可。");
        }
    }
}
