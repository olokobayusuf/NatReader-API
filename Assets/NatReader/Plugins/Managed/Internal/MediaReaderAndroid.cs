/* 
*   NatReader
*   Copyright (c) 2019 Yusuf Olokoba
*/

namespace NatReader.Internal {

    using UnityEngine;
    using System;
    using System.Runtime.InteropServices;

    public sealed class MediaReaderAndroid : INativeMediaReader {

        #region --IMediaReader--

        public MediaReaderAndroid (AndroidJavaObject reader) {
            this.reader = reader;
            this.Unmanaged = new AndroidJavaClass(@"com.olokobayusuf.natrender.Unmanaged");
        }

        public void Dispose () {
            reader.Call(@"release");
        }

        public bool CopyNextFrame (IntPtr dstBuffer, out int dstSize, out long timestamp) {
            var sampleBuffer = reader.Call<AndroidJavaObject>(@"copyNextFrame");
            timestamp = sampleBuffer.Get<long>(@"timestamp");
            try {
                var pixelBuffer = sampleBuffer.Get<AndroidJavaObject>(@"buffer");
                dstSize = pixelBuffer.Call<int>(@"capacity");
                var srcBuffer = (IntPtr)Unmanaged.CallStatic<long>(@"baseAddress", pixelBuffer);
                memcpy(dstBuffer, srcBuffer, (UIntPtr)dstSize);
                pixelBuffer.Dispose();
                return true;
            } catch {
                dstSize = 0;
                return false;
            } finally {
                sampleBuffer.Dispose();
            }
        }
        #endregion


        #region --Operations--

        private readonly AndroidJavaObject reader;
        private readonly AndroidJavaClass Unmanaged;

        #if UNITY_ANDROID && !UNITY_EDITOR
        [DllImport(@"c")]
        private static extern IntPtr memcpy (IntPtr dst, IntPtr src, UIntPtr size);
        #else
        private static IntPtr memcpy (IntPtr dst, IntPtr src, UIntPtr size) { return dst; }
        #endif
        #endregion
    }
}