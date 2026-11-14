package ro.coroli.monitor

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/**
 * USB part, step 1: detect UVC (video class) devices.
 * Step 2 (next): hook a UVC library to render the preview.
 */
object UsbSource {
    fun findVideoDevices(ctx: Context): List<UsbDevice> {
        val m = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        return m.deviceList.values.filter { d ->
            (0 until d.interfaceCount).any {
                d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO
            }
        }
    }
}
