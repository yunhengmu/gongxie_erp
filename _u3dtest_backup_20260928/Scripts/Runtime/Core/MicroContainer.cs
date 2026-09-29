using System;
using System.Collections.Generic;

namespace AgvDemo.Core
{
    /// <summary>
    /// 极简依赖注入容器：够用、可控，作为 Composition Root 的装配地基。
    /// 支持「延迟单例」与「预先实例」两种注册；不引入第三方包，便于替换成 VContainer。
    /// </summary>
    public sealed class MicroContainer
    {
        readonly Dictionary<Type, Func<MicroContainer, object>> _registrations =
            new Dictionary<Type, Func<MicroContainer, object>>();
        readonly Dictionary<Type, object> _singletons = new Dictionary<Type, object>();

        /// <summary>注册为延迟单例：首次 Resolve 时创建一次，之后复用同一实例。</summary>
        public MicroContainer Register<T>(Func<MicroContainer, T> factory)
        {
            _registrations[typeof(T)] = c => factory(c);
            return this;
        }

        /// <summary>注册为预先构建好的实例（Scoped/显式所有权由调用方负责）。</summary>
        public MicroContainer Instance<T>(T instance) where T : class
        {
            _singletons[typeof(T)] = instance;
            return this;
        }

        public T Resolve<T>() where T : class
        {
            var type = typeof(T);

            if (_singletons.TryGetValue(type, out var baked))
                return (T)baked;

            if (_registrations.TryGetValue(type, out var factory))
            {
                var built = factory(this);
                _singletons[type] = built;   // 延迟单例：成功后缓存
                return (T)built;
            }

            throw new InvalidOperationException($"未注册服务: {type.FullName}");
        }

        public bool TryResolve<T>(out T instance) where T : class
        {
            var type = typeof(T);

            if (_singletons.TryGetValue(type, out var baked))
            {
                instance = (T)baked;
                return true;
            }

            if (_registrations.TryGetValue(type, out var factory))
            {
                var built = factory(this);
                _singletons[type] = built;
                instance = (T)built;
                return true;
            }

            instance = null;
            return false;
        }

        public void Clear()
        {
            _registrations.Clear();
            _singletons.Clear();
        }
    }
}