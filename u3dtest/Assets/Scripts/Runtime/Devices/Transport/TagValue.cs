namespace AgvDemo.Devices.Transport
{
    public enum TagType
    {
        Bool,
        Int,
        Real,
        Text,
    }

    /// <summary>
    /// PLC 标签值的类型化载体，对齐 S7 的 BOOL / INT / REAL / STRING。
    /// 用无装箱的结构体承载，避免点位轮询时产生 GC。
    /// </summary>
    public readonly struct TagValue
    {
        public readonly TagType Type;

        readonly bool _bool;
        readonly int _int;
        readonly float _real;
        readonly string _text;

        TagValue(TagType type, bool b, int i, float r, string s)
        {
            Type = type;
            _bool = b;
            _int = i;
            _real = r;
            _text = s;
        }

        public static TagValue From(bool v) => new TagValue(TagType.Bool, v, 0, 0f, null);
        public static TagValue From(int v) => new TagValue(TagType.Int, false, v, 0f, null);
        public static TagValue From(float v) => new TagValue(TagType.Real, false, 0, v, null);
        public static TagValue From(string v) => new TagValue(TagType.Text, false, 0, 0f, v ?? string.Empty);

        public bool AsBool() => Type == TagType.Bool ? _bool : _real != 0f;
        public int AsInt() => Type == TagType.Int ? _int : (int)_real;
        public float AsReal() => Type == TagType.Real ? _real : _int;
        public string AsText() => _text ?? string.Empty;

        public override string ToString()
        {
            switch (Type)
            {
                case TagType.Bool: return _bool ? "TRUE" : "FALSE";
                case TagType.Int: return _int.ToString();
                case TagType.Real: return _real.ToString("0.###");
                default: return _text ?? string.Empty;
            }
        }
    }
}
