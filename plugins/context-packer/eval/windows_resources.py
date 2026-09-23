"""Windows process and C: resource snapshot for the bounded local Laya replay."""

from __future__ import annotations

import os


def process_resources(pid: int) -> dict[str, int]:
    if os.name != "nt":
        raise RuntimeError("Live soak resource sampling requires Windows; --verify-only works on any OS.")
    import ctypes
    from ctypes import wintypes
    import shutil

    class ProcessMemoryCountersEx(ctypes.Structure):
        _fields_ = [
            ("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD),
            ("PeakWorkingSetSize", ctypes.c_size_t), ("WorkingSetSize", ctypes.c_size_t),
            ("QuotaPeakPagedPoolUsage", ctypes.c_size_t), ("QuotaPagedPoolUsage", ctypes.c_size_t),
            ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t), ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
            ("PagefileUsage", ctypes.c_size_t), ("PeakPagefileUsage", ctypes.c_size_t),
            ("PrivateUsage", ctypes.c_size_t),
        ]

    class ThreadEntry32(ctypes.Structure):
        _fields_ = [
            ("dwSize", wintypes.DWORD), ("cntUsage", wintypes.DWORD),
            ("th32ThreadID", wintypes.DWORD), ("th32OwnerProcessID", wintypes.DWORD),
            ("tpBasePri", wintypes.LONG), ("tpDeltaPri", wintypes.LONG),
            ("dwFlags", wintypes.DWORD),
        ]

    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    psapi = ctypes.WinDLL("psapi", use_last_error=True)
    kernel.OpenProcess.argtypes = (wintypes.DWORD, wintypes.BOOL, wintypes.DWORD)
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.CreateToolhelp32Snapshot.argtypes = (wintypes.DWORD, wintypes.DWORD)
    kernel.CreateToolhelp32Snapshot.restype = wintypes.HANDLE
    kernel.Thread32First.argtypes = (wintypes.HANDLE, ctypes.POINTER(ThreadEntry32))
    kernel.Thread32First.restype = wintypes.BOOL
    kernel.Thread32Next.argtypes = (wintypes.HANDLE, ctypes.POINTER(ThreadEntry32))
    kernel.Thread32Next.restype = wintypes.BOOL
    kernel.CloseHandle.argtypes = (wintypes.HANDLE,)
    kernel.CloseHandle.restype = wintypes.BOOL
    psapi.GetProcessMemoryInfo.argtypes = (wintypes.HANDLE, ctypes.POINTER(ProcessMemoryCountersEx), wintypes.DWORD)
    psapi.GetProcessMemoryInfo.restype = wintypes.BOOL

    process = kernel.OpenProcess(0x1000 | 0x0010, False, pid)
    if not process:
        raise OSError(ctypes.get_last_error(), "Cannot open server process")
    try:
        memory = ProcessMemoryCountersEx()
        memory.cb = ctypes.sizeof(memory)
        if not psapi.GetProcessMemoryInfo(process, ctypes.byref(memory), memory.cb):
            raise OSError(ctypes.get_last_error(), "Cannot read server memory")
    finally:
        kernel.CloseHandle(process)
    snapshot = kernel.CreateToolhelp32Snapshot(0x00000004, 0)
    if snapshot == wintypes.HANDLE(-1).value:
        raise OSError(ctypes.get_last_error(), "Cannot snapshot threads")
    count = 0
    try:
        entry = ThreadEntry32()
        entry.dwSize = ctypes.sizeof(entry)
        success = kernel.Thread32First(snapshot, ctypes.byref(entry))
        while success:
            count += entry.th32OwnerProcessID == pid
            success = kernel.Thread32Next(snapshot, ctypes.byref(entry))
    finally:
        kernel.CloseHandle(snapshot)
    return {
        "rss_bytes": int(memory.WorkingSetSize),
        "private_bytes": int(memory.PrivateUsage),
        "threads": count,
        "c_free_bytes": shutil.disk_usage("C:\\").free,
    }
