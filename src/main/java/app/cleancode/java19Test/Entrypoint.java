package app.cleancode.java19Test;

import java.lang.foreign.MemoryAddress;
import java.lang.foreign.MemorySegment;
import static java.lang.foreign.MemoryAddress.NULL;
import java.lang.foreign.MemorySession;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import app.cleancode.bindings.win32.WINDOWS;
import app.cleancode.bindings.win32.WNDCLASS;
import app.cleancode.bindings.win32.WNDPROC;

public class Entrypoint {

  private static String getUtf16String(MemoryAddress address) {
    String str = "";
    char temp;
    int offset = 0;
    do {
      temp = address.get(ValueLayout.JAVA_CHAR, offset);
      str += temp;
      offset += 2;
    } while (temp != 0);
    return str.substring(0, str.length() - 1);
  }

  private static String getLastErrorMessage() {
    try (MemorySession memorySession = MemorySession.openConfined()) {
      MemorySegment messageAddressPointer = memorySession.allocate(ValueLayout.ADDRESS);
      int errorCode = WINDOWS.GetLastError();
      long messageSize = WINDOWS.FormatMessageA(
          WINDOWS.FORMAT_MESSAGE_ALLOCATE_BUFFER() | WINDOWS.FORMAT_MESSAGE_FROM_SYSTEM()
              | WINDOWS.FORMAT_MESSAGE_IGNORE_INSERTS(),
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
    if (message == WINDOWS.WM_CLOSE()) {
      WINDOWS.DestroyWindow(windowAddress);
      WINDOWS.PostQuitMessage(0);
      return 0;
    } else if (message == WINDOWS.WM_CREATE()) {
      try (MemorySession memorySession = MemorySession.openConfined()) {
        SegmentAllocator allocator = SegmentAllocator.newNativeArena(memorySession);
        if (WINDOWS.AddClipboardFormatListener(windowAddress) != WINDOWS.TRUE()) {
          String errorMessage = getLastErrorMessage();
         WINDOWS.MessageBoxA(windowAddress, allocator.allocateUtf8String(errorMessage), NULL, 0);
          return 1;
        }
      }
    } else if (message == WINDOWS.WM_CLIPBOARDUPDATE()) {
      WINDOWS.OpenClipboard(windowAddress);
      try (MemorySession memorySession = MemorySession.openConfined()) {
        MemoryAddress clipboardContentsPointer =
            WINDOWS.GetClipboardData(WINDOWS.CF_UNICODETEXT());
        if (clipboardContentsPointer == NULL) {
          System.err.println("Could not get the contents of the clipboard as unicode");
        } else {
          String clipboardContents = getUtf16String(clipboardContentsPointer);
          System.out.println(clipboardContents);
          
        }
      } finally {
        WINDOWS.CloseClipboard();
      }
    }
    return WINDOWS.DefWindowProcA(windowAddress, message, param1, param2);
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
      WNDCLASS.hInstance$set(windowClass, WINDOWS.GetModuleHandleA(NULL));
      WINDOWS.RegisterClassA(windowClass);
      var window = WINDOWS.CreateWindowExA(0, classNamePointer,
          allocator.allocateUtf8String("Test Window"), WINDOWS.WS_OVERLAPPEDWINDOW(),
          WINDOWS.CW_USEDEFAULT(), WINDOWS.CW_USEDEFAULT(), WINDOWS.CW_USEDEFAULT(),
          WINDOWS.CW_USEDEFAULT(), NULL, NULL, WINDOWS.GetModuleHandleA(NULL), NULL);
      if (window == NULL) {
        String message = getLastErrorMessage();
        throw new RuntimeException("Failed to create the window: %s".formatted(message));
      }
      /*
       * long showResult = WINDOWS_h.ShowWindow(window, WINDOWS_h.SW_NORMAL()); if (showResult != 0)
       * { String message = getLastErrorMessage(); throw new
       * RuntimeException("Failed to show the window: %s".formatted(message)); }
       */
      var windowMessage = app.cleancode.bindings.win32.MSG.allocate(allocator);
      while (WINDOWS.GetMessageA(windowMessage, NULL, 0, 0) > 0) {
        WINDOWS.TranslateMessage(windowMessage);
        WINDOWS.DispatchMessageA(windowMessage);
      }
    }
  }
}
