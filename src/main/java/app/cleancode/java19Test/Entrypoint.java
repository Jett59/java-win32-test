package app.cleancode.java19Test;

import java.lang.foreign.MemoryAddress;
import java.lang.foreign.MemorySegment;
import static java.lang.foreign.MemoryAddress.NULL;
import java.lang.foreign.MemorySession;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import app.cleancode.bindings.windows.MSG;
import app.cleancode.bindings.windows.PAINTSTRUCT;
import app.cleancode.bindings.windows.WINDOWS_h;
import app.cleancode.bindings.windows.WNDCLASS;
import app.cleancode.bindings.windows.WNDPROC;

public class Entrypoint {

  private static String getLastErrorMessage() {
    try (MemorySession memorySession = MemorySession.openConfined()) {
      MemorySegment messageAddressPointer = memorySession.allocate(ValueLayout.ADDRESS);
      int errorCode = WINDOWS_h.GetLastError();
      long messageSize = WINDOWS_h.FormatMessageA(
          WINDOWS_h.FORMAT_MESSAGE_ALLOCATE_BUFFER() | WINDOWS_h.FORMAT_MESSAGE_FROM_SYSTEM()
              | WINDOWS_h.FORMAT_MESSAGE_IGNORE_INSERTS(),
          NULL, errorCode, 0, messageAddressPointer.address(), 0, NULL);
      MemoryAddress messageAddress = messageAddressPointer.get(ValueLayout.ADDRESS, 0);
      if (messageAddress == NULL) {
        throw new RuntimeException("Failed to fetch the error message");
      }
      MemorySegment messageSegment =
          MemorySegment.ofAddress(messageAddress, messageSize, memorySession);
      String message = new String(messageSegment.toArray(ValueLayout.JAVA_BYTE));
      return message;
    }
  }

  public static long windowEventHandler(MemoryAddress windowAddress, int message, long param1,
      long param2) {
    // Apparently we can't use a switch here because cases have to be constant expressions or
    // something. So we do the next best thing (which is the worst thing.)
    if (message == WINDOWS_h.WM_CLOSE()) {
      WINDOWS_h.DestroyWindow(windowAddress);
      WINDOWS_h.PostQuitMessage(0);
      return 0;
    } else if (message == WINDOWS_h.WM_PAINT()) {
      try (MemorySession memorySession = MemorySession.openConfined()) {
        SegmentAllocator allocator = SegmentAllocator.newNativeArena(memorySession);
        var paintStruct = PAINTSTRUCT.allocate(allocator);
        var hdc = WINDOWS_h.BeginPaint(windowAddress, paintStruct);
        var selectedBrush = WINDOWS_h.CreateSolidBrush(0);
        WINDOWS_h.FillRect(hdc, PAINTSTRUCT.rcPaint$slice(paintStruct),
            selectedBrush);
        WINDOWS_h.EndPaint(windowAddress, paintStruct);
        return 0;
      }
    }
    return WINDOWS_h.DefWindowProcA(windowAddress, message, param1, param2);
  }

  public static void main(String[] args) throws Throwable {
    System.loadLibrary("kernel32");
    System.loadLibrary("user32");
    System.loadLibrary("gdi32");
    try (MemorySession memorySession = MemorySession.openConfined()) {
      SegmentAllocator allocator = SegmentAllocator.newNativeArena(memorySession);
      var windowClass = WNDCLASS.allocate(allocator);
      if (windowClass == NULL) {
        throw new RuntimeException("Failed to allocate the window class struct");
      }
      MemoryAddress windowProc =
          WNDPROC.allocate(Entrypoint::windowEventHandler, memorySession).address();
      WNDCLASS.lpfnWndProc$set(windowClass, windowProc);
      String className = "windowClass";
      MemoryAddress classNamePointer = allocator.allocateUtf8String(className).address();
      WNDCLASS.lpszClassName$set(windowClass, classNamePointer);
      WNDCLASS.hInstance$set(windowClass, WINDOWS_h.GetModuleHandleA(NULL));
      WINDOWS_h.RegisterClassA(windowClass);
      var window = WINDOWS_h.CreateWindowExA(0, classNamePointer,
          allocator.allocateUtf8String("Test Window"), WINDOWS_h.WS_OVERLAPPEDWINDOW(),
          WINDOWS_h.CW_USEDEFAULT(), WINDOWS_h.CW_USEDEFAULT(), WINDOWS_h.CW_USEDEFAULT(),
          WINDOWS_h.CW_USEDEFAULT(), NULL, NULL, WINDOWS_h.GetModuleHandleA(NULL), NULL);
      if (window == NULL) {
        String message = getLastErrorMessage();
        throw new RuntimeException("Failed to create the window: %s".formatted(message));
      }
      long showResult = WINDOWS_h.ShowWindow(window, WINDOWS_h.SW_NORMAL());
      if (showResult != 0) {
        String message = getLastErrorMessage();
        throw new RuntimeException("Failed to show the window: %s".formatted(message));
      }
      var windowMessage = MSG.allocate(allocator);
      while (WINDOWS_h.GetMessageA(windowMessage, NULL, 0, 0) > 0) {
        WINDOWS_h.TranslateMessage(windowMessage);
        WINDOWS_h.DispatchMessageA(windowMessage);
      }
    }
  }
}
