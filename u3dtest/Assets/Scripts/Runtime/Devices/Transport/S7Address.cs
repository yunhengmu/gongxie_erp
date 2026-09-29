using System;

namespace AgvDemo.Devices.Transport
{
    /// <summary>
    /// S7 点位地址，形如 DB1.DBX4.0 / DB1.DBW4 / DB1.DBD4。
    /// S7 的数据是**大端**存放，解析与字节转换都在 SiemensS7Transport 里统一处理。
    /// </summary>
    public readonly struct S7Address
    {
        public readonly int DataBlock;
        public readonly int StartByte;
        public readonly int Bit;
        public readonly TagType Type;

        S7Address(int dataBlock, int startByte, int bit, TagType type)
        {
            DataBlock = dataBlock;
            StartByte = startByte;
            Bit = bit;
            Type = type;
        }

        public int ByteCount
        {
            get
            {
                switch (Type)
                {
                    case TagType.Bool: return 1;
                    case TagType.Int: return 2;
                    default: return 4;
                }
            }
        }

        /// <summary>支持 3 种写法：DBX（位）、DBW（字/int）、DBD（双字/real）。</summary>
        public static bool TryParse(string text, out S7Address address)
        {
            address = default;
            if (string.IsNullOrEmpty(text)) return false;

            // DB<块号>.DB<X|W|D><起始字节>[.<位>]
            int dot = text.IndexOf(".DB", StringComparison.OrdinalIgnoreCase);
            if (dot <= 2) return false;

            if (!int.TryParse(text.Substring(2, dot - 2), out int dataBlock)) return false;

            int cursor = dot + 3;
            if (cursor >= text.Length) return false;

            char size = char.ToUpperInvariant(text[cursor]);
            cursor++;

            int bit = 0;
            int bitDot = text.IndexOf('.', cursor);
            string bytePart = bitDot < 0 ? text.Substring(cursor) : text.Substring(cursor, bitDot - cursor);

            if (!int.TryParse(bytePart, out int startByte)) return false;
            if (bitDot >= 0 && !int.TryParse(text.Substring(bitDot + 1), out bit)) return false;

            switch (size)
            {
                case 'X':
                    address = new S7Address(dataBlock, startByte, bit, TagType.Bool);
                    return true;
                case 'W':
                    address = new S7Address(dataBlock, startByte, 0, TagType.Int);
                    return true;
                case 'D':
                    address = new S7Address(dataBlock, startByte, 0, TagType.Real);
                    return true;
                default:
                    return false;
            }
        }

        public override string ToString()
            => Type == TagType.Bool
                ? $"DB{DataBlock}.DBX{StartByte}.{Bit}"
                : $"DB{DataBlock}.DB{(Type == TagType.Int ? 'W' : 'D')}{StartByte}";
    }
}
