using System;
using AgvDemo.Core;

namespace AgvDemo.Gameplay
{
    /// <summary>
    /// 组合根（Composition Root）：程序启动时组装整张对象图，组件从这里解析依赖。
    /// 装配是幂等且可累加的 —— 多个入口各自注册自己关心的服务，谁先运行都不影响结果。
    /// 当前用自带的轻量 MicroContainer；换成 VContainer 只需替换本类内部的实现。
    /// </summary>
    public static class DemoHost
    {
        static MicroContainer _container;

        public static bool IsReady => _container != null;

        static MicroContainer Container => _container ?? (_container = new MicroContainer());

        /// <summary>累加式装配。可以安全地被多个入口各调用一次。</summary>
        public static void Install(Action<MicroContainer> configure)
        {
            configure?.Invoke(Container);
        }

        public static void Register<T>(Func<MicroContainer, T> factory)
        {
            Container.Register(factory);
        }

        public static void Instance<T>(T instance) where T : class
        {
            Container.Instance(instance);
        }

        public static T Resolve<T>() where T : class
        {
            if (_container == null)
                throw new InvalidOperationException($"DemoHost 尚未装配，无法解析 {typeof(T).Name}。");
            return _container.Resolve<T>();
        }

        /// <summary>解析失败返回 null，用于「可选依赖」场景。</summary>
        public static T ResolveOrDefault<T>() where T : class
        {
            if (_container == null) return null;
            return _container.TryResolve<T>(out var instance) ? instance : null;
        }

        /// <summary>供 UNITY_EDITOR 或测试清理。运行时整场生存，一般不需要调用。</summary>
        public static void Reset()
        {
            _container?.Clear();
            _container = null;
        }
    }
}
