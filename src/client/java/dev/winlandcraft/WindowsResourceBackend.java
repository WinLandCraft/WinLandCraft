package dev.winlandcraft;

import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.*;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinBase.FILETIME;
import com.sun.jna.platform.win32.BaseTSD.SIZE_T;
import com.sun.jna.win32.StdCallLibrary;
import java.util.*;

/** Direct Windows APIs, loaded only when ResourceSampler selects this backend. */
final class WindowsResourceBackend implements ResourceSampler.Backend {
    private interface MemoryApi extends StdCallLibrary {
        MemoryApi INSTANCE=Native.load("psapi",MemoryApi.class);
        boolean GetProcessMemoryInfo(HANDLE process,MemoryCounters counters,int size);
    }
    @Structure.FieldOrder({"cb","faults","peak","working","quotaPeakPaged","quotaPaged","quotaPeakNonPaged","quotaNonPaged","pagefile","peakPagefile"})
    public static class MemoryCounters extends Structure {
        public int cb,faults;public SIZE_T peak,working,quotaPeakPaged,quotaPaged,quotaPeakNonPaged,quotaNonPaged,pagefile,peakPagefile;
    }
    private Map<Integer,Integer> threads=Map.of();private long threadSample;
    private void refreshThreads() {
        if(System.nanoTime()-threadSample<50_000_000L)return;
        Map<Integer,Integer> result=new HashMap<>();
        HANDLE snapshot=Kernel32.INSTANCE.CreateToolhelp32Snapshot(Tlhelp32.TH32CS_SNAPPROCESS,new WinDef.DWORD(0));
        if(!WinBase.INVALID_HANDLE_VALUE.equals(snapshot))try {
            var entry=new Tlhelp32.PROCESSENTRY32();
            if(Kernel32.INSTANCE.Process32First(snapshot,entry))do result.put(entry.th32ProcessID.intValue(),entry.cntThreads.intValue());
            while(Kernel32.INSTANCE.Process32Next(snapshot,entry));
        } finally {Kernel32.INSTANCE.CloseHandle(snapshot);}
        threads=result;threadSample=System.nanoTime();
    }
    @Override public ResourceSampler.Raw read(ProcessHandle process) {
        refreshThreads();int pid=(int)process.pid();
        HANDLE handle=Kernel32.INSTANCE.OpenProcess(WinNT.PROCESS_QUERY_INFORMATION|WinNT.PROCESS_VM_READ,false,pid);
        if(handle==null)return null;
        try {
            var created=new FILETIME();var exited=new FILETIME();var kernel=new FILETIME();var user=new FILETIME();
            if(!Kernel32.INSTANCE.GetProcessTimes(handle,created,exited,kernel,user))return null;
            var memory=new MemoryCounters();memory.cb=memory.size();
            long ram=MemoryApi.INSTANCE.GetProcessMemoryInfo(handle,memory,memory.size())?memory.working.longValue():-1;
            var io=new WinNT.IO_COUNTERS();boolean hasIo=Kernel32.INSTANCE.GetProcessIoCounters(handle,io);
            return new ResourceSampler.Raw(pid,ticks(created),(ticks(kernel)+ticks(user))/10000,ram,threads.getOrDefault(pid,0),
                    hasIo?io.ReadTransferCount:-1,hasIo?io.WriteTransferCount:-1);
        } finally {Kernel32.INSTANCE.CloseHandle(handle);}
    }
    private static long ticks(FILETIME value){return ((long)value.dwHighDateTime<<32)|Integer.toUnsignedLong(value.dwLowDateTime);}
    @Override public String name(){return "Windows native APIs";}
}
