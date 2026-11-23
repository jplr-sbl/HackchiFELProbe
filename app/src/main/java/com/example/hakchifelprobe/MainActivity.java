package com.example.hakchifelprobe;

import android.app.*;
import android.os.*;
import android.content.*;
import android.hardware.usb.*;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.zip.CRC32;
import android.os.StatFs;
import android.net.Uri;

public class MainActivity extends Activity {
  static final int VID=0x1F3A, PID=0xEFE8;
  static final String PERMISSION="com.example.hakchifelprobe.USB_PERMISSION";
  UsbManager manager;
  UsbDevice device;
  TextView status, log;
  Button permissionButton, probeButton;
  boolean auditWaitingForPermission=false;
  volatile boolean auditRunning=false;
  Button auditButton;
  final BroadcastReceiver receiver = new BroadcastReceiver() {
    @Override public void onReceive(Context c, Intent i) {
      if (!PERMISSION.equals(i.getAction())) return;
      boolean granted=i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false);
      append(granted ? "Permission granted" : "Permission denied");
      scan();
      if(auditWaitingForPermission){
        auditWaitingForPermission=false;
        if(granted)startAudit();
        else append("Audit paused: grant USB permission and tap Run again.");
      }
    }
  };
  @Override public void onCreate(Bundle b) {
    super.onCreate(b);
    manager=(UsbManager)getSystemService(USB_SERVICE);
    ScrollView pageScroll=new ScrollView(this);
    pageScroll.setFillViewport(true);
    LinearLayout root=new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(18,22,18,14);
    root.setBackgroundColor(Color.rgb(13,19,30));
    TextView title=new TextView(this);
    title.setText("HAKCHI FEL  •  v4.9 ONE BUTTON"); title.setTextSize(22); title.setTextColor(Color.CYAN);
    root.addView(title);
    TextView subtitle=new TextView(this);
    subtitle.setText("NES Classic  /  Android USB toolkit");
    subtitle.setTextColor(Color.LTGRAY); subtitle.setTextSize(12);
    root.addView(subtitle);
    status=new TextView(this); status.setTextColor(Color.WHITE); status.setTextSize(14);
    status.setPadding(0,12,0,12);root.addView(status);

    LinearLayout actions=new LinearLayout(this);
    actions.setOrientation(LinearLayout.VERTICAL);
    auditButton=button(actions,"Run USB + bundled checks",v->runBundledAllV32());
    button(actions,"ALL STAGES: Guide and test in order",v->showAllStagesV48());
    





    button(actions,"Export JSON report",v->exportDiagnosticJson());
    root.addView(actions);

    TextView note=new TextView(this);
    note.setText("READ-ONLY  •  Storage backup and flashing are not available");
    note.setTextSize(12);note.setTextColor(Color.YELLOW);
    note.setPadding(0,8,0,8);root.addView(note);

    Button advancedToggle=button(root,"Show advanced tools  ▾",null);
    LinearLayout advanced=new LinearLayout(this);
    advanced.setOrientation(LinearLayout.VERTICAL);
    advanced.setVisibility(View.GONE);
    advancedToggle.setOnClickListener(v->{
      boolean show=advanced.getVisibility()!=View.VISIBLE;
      advanced.setVisibility(show?View.VISIBLE:View.GONE);
      advancedToggle.setText(show?"Hide advanced tools  ▴":"Show advanced tools  ▾");
    });
    button(advanced,"Verify U-Boot identity + storage gate",v->inspectSavedOrChoose());
    button(advanced,"Scan USB",v->scan());
    permissionButton=button(advanced,"Request USB permission",v->request());
    probeButton=button(advanced,"Open FEL interface",v->probe());
    button(advanced,"Read FEL version",v->new Thread(()->readFelVersion(1)).start());
    button(advanced,"Version reliability ×5",v->new Thread(()->readFelVersion(5)).start());
    button(advanced,"Read scratch RAM",v->new Thread(()->readScratchRam(1)).start());
    button(advanced,"RAM reliability ×5",v->new Thread(()->readScratchRam(5)).start());
    button(advanced,"Console identification",v->new Thread(()->identifyConsole()).start());
    button(actions,"ONE BUTTON: Connect + verify NES Classic",v->confirmOneTapV49());
    button(actions,"Stage 2: FES1 SRAM loader (experimental)",v->confirmLoaderV45());
    button(actions,"Stage 3: Verify DRAM (read-only)",v->new Thread(()->runStage3V46()).start());
    button(actions,"Stage 4: Upload + verify U-Boot in DRAM",v->confirmUbootStageV47());
    button(actions,"Stage 5: Storage / backup / Hakchi gate",v->new Thread(()->runStage5V46()).start());
    button(advanced,"Backup readiness assessment",v->new Thread(()->backupReadiness()).start());
    button(advanced,"Firmware backup requirements",v->showBackupRequirements());
    root.addView(advanced);
    TextView logTitle=new TextView(this);
    logTitle.setText("SESSION LOG");logTitle.setTextSize(12);
    logTitle.setTextColor(Color.LTGRAY);logTitle.setPadding(0,10,0,6);
    root.addView(logTitle);
    ScrollView scroll=new ScrollView(this);
    log=new TextView(this); log.setTextColor(Color.GREEN); log.setTextSize(12);
    log.setTextIsSelectable(true);log.setPadding(8,8,8,8);
    scroll.addView(log);
    root.addView(scroll,new LinearLayout.LayoutParams(-1,320));
    LinearLayout footer=new LinearLayout(this);
    footer.setOrientation(LinearLayout.HORIZONTAL);
    Button share=button(footer,"Share log",v->{Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_TEXT,log.getText().toString());startActivity(Intent.createChooser(i,"Share diagnostic log"));});
    Button clear=button(footer,"Clear log",v->log.setText(""));
    share.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
    clear.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
    root.addView(footer);
    pageScroll.addView(root);
    setContentView(pageScroll);
    IntentFilter f=new IntentFilter(PERMISSION);
    if(Build.VERSION.SDK_INT>=33) registerReceiver(receiver,f,Context.RECEIVER_EXPORTED);
    else registerReceiver(receiver,f);
    scan();
  }
  volatile boolean oneTapRunningV49=false;
  void confirmOneTapV49(){
    if(oneTapRunningV49){report("One-button sequence is already running");return;}
    new AlertDialog.Builder(this).setTitle("One-button NES Classic communication")
      .setMessage("This runs the proven hardware communication sequence automatically: FES1 SRAM load/execute, DRAM check, and full U-Boot DRAM upload/readback. It changes volatile RAM and may require power cycling if communication fails. It DOES NOT execute U-Boot, read or edit internal flash, install games, or back up firmware. Proceed?")
      .setNegativeButton("Cancel",null)
      .setPositiveButton("Run communication sequence",(dialog,which)->new Thread(()->runOneTapV49()).start()).show();
  }
  void runOneTapV49(){
    if(oneTapRunningV49)return;
    oneTapRunningV49=true;
    try{
      report("=== ONE BUTTON: NES CLASSIC COMMUNICATION SEQUENCE ===");
      if(device==null||!manager.hasPermission(device)){
        report("STOP: Connect NES Classic in FEL mode and grant USB permission first");return;
      }
      dramVerifiedV46=false;ubootStagedV47=false;
      report("STEP 1/3: Load FES1 and request DRAM initialization");
      runLoaderV45();
      try{Thread.sleep(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();report("STOP: Interrupted");return;}
      report("STEP 2/3: Verify preliminary DRAM communication");
      runStage3V46();
      if(!dramVerifiedV46){report("STOP: DRAM verification failed; U-Boot upload skipped");return;}
      report("STEP 3/3: Upload U-Boot to DRAM and verify all bytes");
      runUbootStageV47();
      if(!ubootStagedV47){report("STOP: U-Boot upload/readback failed");return;}
      report("PASS: ONE-BUTTON COMMUNICATION SEQUENCE COMPLETE");
      report("NEXT DEVELOPMENT REQUIRED: U-Boot execution/reconnect and read-only flash access. Internal storage editing is NOT available in this build.");
    }finally{oneTapRunningV49=false;}
  }
  void showAllStagesV48(){
    new AlertDialog.Builder(this).setTitle("One APK: all hardware stages")
      .setMessage("Run the steps in order. Each step is included in this APK.\n\n1. USB and bundled binary audit (read-only)\n2. FES1 SRAM upload and execution (experimental)\n3. DRAM read verification\n4. U-Boot DRAM upload and full verification (experimental)\n5. Storage, backup, Hakchi and game-management status\n\nSteps 2 and 4 require separate consent. U-Boot execution, live flash reads, backups and game management are not implemented; this app will not pretend those tests passed. No firmware flash writes are available.")
      .setPositiveButton("Start with USB audit",(dialog,which)->runBundledAllV32())
      .setNegativeButton("Close",null).show();
  }
  void beginAudit(){
    if(auditRunning||auditWaitingForPermission){append("Audit already running or waiting for USB permission.");return;}
    scan();
    if(device==null){append("BLOCKED: Connect NES Classic in FEL mode, then retry.");return;}
    if(!manager.hasPermission(device)){
      auditWaitingForPermission=true;
      append("USB permission required. Please approve the Android prompt; audit will start automatically.");
      request();
      return;
    }
    startAudit();
  }
  void startAudit(){
    if(auditRunning)return;
    auditRunning=true;
    auditButton.setEnabled(false);
    auditButton.setText("Running read-only audit...");
    append("USB permission confirmed; starting read-only audit.");
    new Thread(()->{
      try{runAllTests();}
      catch(Exception e){report("FAIL: Audit interrupted: "+e.getMessage());}
      finally{runOnUiThread(()->{
        auditRunning=false;
        auditButton.setEnabled(true);
        auditButton.setText("Run complete READ-ONLY audit");
      });}
    }).start();
  }
  static final int INSPECT_LOADER=4106, INSPECT_FES1=4107;
  boolean allInOnePending=false;
  void runAllInOne(){
    append("=== ALL-IN-ONE: USB diagnostics and saved loader inspection ===");
    Uri saved=savedLoaderUri();
    if(saved==null){
      append("Select the loader once. The app will remember the file where Android permits.");
      allInOnePending=true;selectLoaderFile();return;
    }
    beginAudit();
    new Thread(()->inspectLoaderFile(saved)).start();
  }
  Uri savedLoaderUri(){
    String value=getPreferences(MODE_PRIVATE).getString("loader_uri","");
    if(value.isEmpty())return null;
    try{return Uri.parse(value);}catch(Exception e){return null;}
  }
  void inspectSavedOrChoose(){
    Uri saved=savedLoaderUri();
    if(saved!=null)new Thread(()->inspectLoaderFile(saved)).start();
    else selectLoaderFile();
  }

  static byte[] felMessageV40(int command,long address,long length){
    if(command<0||command>65535||address<0||address>0xffffffffL||length<0||length>0xffffffffL)
      throw new IllegalArgumentException("FEL message field out of range");
    ByteBuffer b=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
    b.putShort((short)command);b.putShort((short)0);
    b.putInt((int)address);b.putInt((int)length);b.putInt(0);
    return b.array();
  }
  static byte[] awUsbHeaderV40(boolean read,int length){
    if(length<0||length>0x2000000)throw new IllegalArgumentException("Invalid AWUSB length");
    ByteBuffer b=ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
    b.put(new byte[]{'A','W','U','C'});
    b.putInt(0);b.putInt(length);
    b.position(15);b.put((byte)0x0c);b.put((byte)(read?0x11:0x12));
    b.position(18);b.putInt(length);
    return b.array();
  }
  static int bootCommandOffsetV40(byte[] data){
    byte[] needle="bootcmd=".getBytes(StandardCharsets.US_ASCII);
    for(int i=0;i<=data.length-needle.length;i++){
      boolean match=true;
      for(int j=0;j<needle.length;j++)if(data[i+j]!=needle[j]){match=false;break;}
      if(match)return i+needle.length;
    }
    return -1;
  }
  void storageSessionPreviewV40(){
    report("=== v4.0 read-only storage session engineering preview ===");
    report("Mode: OFFLINE protocol validation; hardware execution disabled.");
    try{
      byte[] upload=felMessageV40(0x0103,0x47000000L,0x20);
      byte[] header=awUsbHeaderV40(false,16);
      boolean fields=upload.length==16&&ByteBuffer.wrap(upload).order(ByteOrder.LITTLE_ENDIAN).getInt(4)==0x47000000;
      boolean envelope=header.length==32&&header[0]=='A'&&header[3]=='C'&&header[15]==12&&header[16]==0x12;
      report((fields?"PASS":"FAIL")+": 16-byte FEL little-endian message encoding");
      report((envelope?"PASS":"FAIL")+": 32-byte AWUSB transport envelope encoding");
      byte[] uboot;
      try(InputStream input=getAssets().open("uboot.bin");ByteArrayOutputStream out=new ByteArrayOutputStream()){
        byte[] b=new byte[8192];int n;
        while((n=input.read(b))!=-1){if(n>0)out.write(b,0,n);}
        uboot=out.toByteArray();
      }
      int offset=bootCommandOffsetV40(uboot);
      String command=flashReadCommandV30(0x600000L,0x400000L);
      boolean fits=offset>=0&&offset+command.length()+1<uboot.length;
      report((fits?"PASS":"FAIL")+": U-Boot bootcmd field located; command fits within image (offset "+offset+")");
      report("Prepared flash READ command: "+command);
      report("Planned transfer buffer: 0x47400000; planned read length: 0x400000 bytes");
      report("DRAM init: NOT EXECUTED; U-Boot: NOT EXECUTED; storage: NOT ACCESSED.");
      report("Hardware stage requires verified memory writes, loader execution, reconnect, and recovery validation.");
    }catch(Exception e){report("FAIL: v4.0 protocol preview: "+e.getMessage());}
  }
  void validateUbootSessionV42(){
    report("=== v4.2 U-Boot session preflight (OFFLINE) ===");
    try{
      byte[] uboot;
      try(InputStream in=getAssets().open("uboot.bin");ByteArrayOutputStream out=new ByteArrayOutputStream()){
        byte[] buf=new byte[8192];int n;
        while((n=in.read(buf))!=-1)if(n>0)out.write(buf,0,n);
        uboot=out.toByteArray();
      }
      int offset=bootCommandOffsetV40(uboot);
      String cmd=flashReadCommandV30(0x600000L,0x400000L);
      byte[] ascii=(cmd+((char)0)).getBytes(StandardCharsets.US_ASCII);
      boolean valid=offset>=0&&offset+ascii.length<=uboot.length;
      report((valid?"PASS":"FAIL")+": U-Boot bootcmd patch location and command length");
      if(valid){
        byte[] copy=uboot.clone();
        System.arraycopy(ascii,0,copy,offset,ascii.length);
        report((copy[offset]==ascii[0]&&copy[offset+ascii.length-1]==0?"PASS":"FAIL")+": command injected into RAM-only image copy");
      }
      report("Reconnect handling: NOT TESTED; console U-Boot execution: DISABLED.");
    }catch(Exception e){report("FAIL: v4.2 preflight: "+e.getMessage());}
  }
  void validateFlashReadPlanV43(){
    report("=== v4.3 flash read planning (OFFLINE, NO STORAGE ACCESS) ===");
    try{
      long[] offsets={0,0x600000L,0x2000000L};
      long[] sizes={0x20000L,0x400000L,0x2000000L};
      for(int i=0;i<offsets.length;i++){
        String cmd=flashReadCommandV30(offsets[i],sizes[i]);
        report("PASS: aligned read plan "+(i+1)+": "+cmd);
      }
      boolean rejected=false;
      try{flashReadCommandV30(1,0x20000L);}catch(IllegalArgumentException e){rejected=true;}
      report((rejected?"PASS":"FAIL")+": unaligned flash address rejected");
      report("No flash reads performed. Storage capacity, partitions and Hakchi install remain UNKNOWN.");
      report("Hardware read requires verified FES1 execution, U-Boot launch and reconnect; not enabled.");
    }catch(Exception e){report("FAIL: v4.3 flash read plan: "+e.getMessage());}
  }
  void runBundledAllV32(){
    append("=== v4.4 ALL-IN-ONE: bundled binaries, protocol, USB and live loader-memory preflight ===");
    new Thread(()->{
      verifyBundledV32("FES1","fes1.bin",12624,"a64371dc69714e78132af03bac874278b91bb87e");
      verifyBundledV32("U-Boot","uboot.bin",573440,"0c743b303f129853edef7bb5c1c40d64a355ab03");
      storageSessionPreviewV40();
      validateUbootSessionV42();
      validateFlashReadPlanV43();
      report("=== Bundled binaries and v4.0 protocol preview complete; USB audit follows ===");
      runOnUiThread(()->beginAudit());
    }).start();
  }
  void verifyBundledV32(String label,String name,int expectedSize,String expectedSha){
    report("--- Bundled "+label+" identity check ---");
    try(InputStream input=getAssets().open(name)){
      ByteArrayOutputStream out=new ByteArrayOutputStream();
      byte[] buf=new byte[8192];int n;
      while((n=input.read(buf))!=-1){
        if(n==0)continue;
        if(out.size()+n>1024*1024)throw new Exception("Binary too large");
        out.write(buf,0,n);
      }
      byte[] data=out.toByteArray();
      MessageDigest sha=MessageDigest.getInstance("SHA-1");
      sha.update(("blob "+data.length+((char)0)).getBytes(StandardCharsets.US_ASCII));
      sha.update(data);
      String gitSha=hexDigest(sha.digest());
      report((data.length==expectedSize&&gitSha.equals(expectedSha)?"PASS":"FAIL")+": "+label+
        " bundled "+data.length+" bytes; Git blob "+gitSha);
    }catch(Exception e){report("FAIL: bundled "+label+" verification: "+e.getMessage());}
  }
  static final String FES1_UPSTREAM_GIT_SHA1="a64371dc69714e78132af03bac874278b91bb87e";
  static final String UBOOT_UPSTREAM_GIT_SHA1="0c743b303f129853edef7bb5c1c40d64a355ab03";
  static String hexDigest(byte[] data){
    StringBuilder h=new StringBuilder();
    for(byte b:data)h.append(String.format(Locale.US,"%02x",b&255));
    return h.toString();
  }
  void verifyUpstreamBlobV31(String label,Uri uri,long expectedSize,String expectedGitSha){
    report("--- v3.1 "+label+" upstream Git blob verification ---");
    if(uri==null){report("BLOCKED: "+label+" file not selected");return;}
    try{
      ByteArrayOutputStream bytes=new ByteArrayOutputStream();
      try(InputStream input=getContentResolver().openInputStream(uri)){
        if(input==null)throw new Exception("Cannot open selected file");
        byte[] b=new byte[8192];int n;
        while((n=input.read(b))!=-1){
          if(n==0)continue;
          if(bytes.size()+n>16*1024*1024)throw new Exception("File exceeds 16 MiB limit");
          bytes.write(b,0,n);
        }
      }
      byte[] data=bytes.toByteArray();
      MessageDigest sha1=MessageDigest.getInstance("SHA-1");
      byte[] gitHeader=("blob "+data.length+((char)0)).getBytes(StandardCharsets.US_ASCII);
      sha1.update(gitHeader);sha1.update(data);
      String gitSha=hexDigest(sha1.digest());
      String sha256=hexDigest(MessageDigest.getInstance("SHA-256").digest(data));
      report("File bytes: "+data.length+"; SHA-256: "+sha256);
      report("Computed Git blob SHA-1: "+gitSha);
      report("Published ClusterM/hakchi2 stable Git blob SHA-1: "+expectedGitSha);
      boolean match=data.length==expectedSize&&gitSha.equalsIgnoreCase(expectedGitSha);
      report((match?"PASS":"FAIL")+": "+label+" byte-for-byte Git blob identity "+(match?"MATCHES":"DOES NOT MATCH")+" original stable branch snapshot");
      report("Note: matching upstream repository content does not independently establish device safety.");
    }catch(Exception e){report("FAIL: "+label+" upstream verification: "+e.getMessage());}
  }
  void verifyBothUpstreamV31(){
    report("=== v3.1 upstream file verification (OFFLINE, NO USB WRITES) ===");
    report("Reference: github.com/ClusterM/hakchi2 stable/data/");
    verifyUpstreamBlobV31("FES1",savedUriV31("fes1_uri"),12624,FES1_UPSTREAM_GIT_SHA1);
    verifyUpstreamBlobV31("U-Boot",savedLoaderUri(),573440,UBOOT_UPSTREAM_GIT_SHA1);
    report("Hardware loader execution and flash operations remain DISABLED.");
  }
  Uri savedUriV31(String key){
    String raw=getPreferences(MODE_PRIVATE).getString(key,"");
    try{return raw.isEmpty()?null:Uri.parse(raw);}catch(Exception e){return null;}
  }
  void selectFes1File(){
    Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType("*/*");
    startActivityForResult(intent,INSPECT_FES1);
  }
  void inspectFes1(Uri uri){
    report("--- v3.0 FES1 DRAM initializer inspection (OFFLINE) ---");
    try(InputStream input=getContentResolver().openInputStream(uri)){
      if(input==null)throw new Exception("File could not be opened");
      MessageDigest md=MessageDigest.getInstance("SHA-256");
      byte[] buf=new byte[8192];long size=0;int n;
      while((n=input.read(buf))!=-1){
        if(n==0)continue;
        size+=n;if(size>1024*1024)throw new Exception("FES1 file exceeds 1 MiB limit");
        md.update(buf,0,n);
      }
      StringBuilder hash=new StringBuilder();
      for(byte b:md.digest())hash.append(String.format(Locale.US,"%02x",b&255));
      report("FES1 size: "+size+" bytes; SHA-256: "+hash);
      report("Upstream Hakchi stable data/fes1.bin reports 12,624 bytes.");
      report("Size comparison: "+(size==12624?"MATCHES published size; NOT proof of integrity":"DIFFERS from published size"));
      report("FES1 execution: DISABLED; DRAM initialization: NOT ATTEMPTED.");
    }catch(Exception e){report("FES1 inspection failed: "+e.getMessage());}
  }
  static String flashReadCommandV30(long address,long length){
    final long sector=0x20000L, max=sector*0x100L;
    if(address<0||length<=0||address%sector!=0||length%sector!=0||length>max)
      throw new IllegalArgumentException("Unaligned or out-of-range flash read");
    long transferBase=0x47400000L;
    return String.format(Locale.US,"sunxi_flash phy_read %x %x %x;efex_test",
      transferBase,address/sector,length/sector);
  }
  void protocolSelfTestsV30(){
    report("--- v3.0 original Hakchi FEL/flash protocol OFFLINE self-tests ---");
    report("Reference: ClusterM/hakchi2 stable FelLib/Fel.cs");
    report("FES1 SRAM load address: 0x00002000; DRAM base: 0x40000000");
    report("U-Boot DRAM address: 0x47000000; transfer buffer: 0x47400000");
    report("Flash sector size: 0x20000 (131072 bytes); maximum read chunk: 0x2000000 (32 MiB)");
    try{
      String expected="sunxi_flash phy_read 47400000 30 20;efex_test";
      String actual=flashReadCommandV30(0x600000L,0x400000L);
      if(!expected.equals(actual))throw new Exception("Unexpected command: "+actual);
      report("PASS: Flash read command formatting matches upstream example: "+actual);
      boolean blocked=false;
      try{flashReadCommandV30(1,0x20000);}catch(IllegalArgumentException e){blocked=true;}
      if(!blocked)throw new Exception("Misaligned address accepted");
      blocked=false;
      try{flashReadCommandV30(0,0x20001);}catch(IllegalArgumentException e){blocked=true;}
      if(!blocked)throw new Exception("Misaligned length accepted");
      blocked=false;
      try{flashReadCommandV30(0,0x2020000);}catch(IllegalArgumentException e){blocked=true;}
      if(!blocked)throw new Exception("Oversized transfer accepted");
      report("PASS: Unaligned addresses, lengths and oversized chunks rejected");
      report("PASS: No USB communication used by offline command encoder.");
    }catch(Exception e){report("FAIL: Offline protocol test: "+e.getMessage());}
    report("FES1 execution, U-Boot execution, flash read/write: DISABLED.");
    report("Next: independent loader validation and controlled recovery plan before hardware access.");
  }
  void selectLoaderFile(){
    Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType("*/*");
    startActivityForResult(intent,INSPECT_LOADER);
  }
  static long be32(byte[] b,int p){return ((long)(b[p]&255)<<24)|((long)(b[p+1]&255)<<16)|((long)(b[p+2]&255)<<8)|(b[p+3]&255);}
  static long le32(byte[] b,int p){return ((long)(b[p]&255))|((long)(b[p+1]&255)<<8)|((long)(b[p+2]&255)<<16)|((long)(b[p+3]&255)<<24);}
  static String ascii(byte[] b,int start,int len){
    StringBuilder x=new StringBuilder();
    for(int i=start;i<start+len;i++){int c=b[i]&255;if(c==0)break;x.append(c>=32&&c<=126?(char)c:'.');}
    return x.toString();
  }
  void inspectLoaderFile(Uri uri){
    report("--- v2.0 candidate identity + v2.1 storage discovery prerequisites ---");
    report("Local inspection only; no USB transfers, RAM writes, boot or flash commands.");
    try(InputStream input=getContentResolver().openInputStream(uri)){
      if(input==null)throw new Exception("Cannot open selected file");
      MessageDigest digest=MessageDigest.getInstance("SHA-256");
      byte[] buf=new byte[8192],head=new byte[512];long size=0;
      ByteArrayOutputStream captured=new ByteArrayOutputStream();
      int n;CRC32 payloadCrc=new CRC32();
      while((n=input.read(buf))!=-1){
        if(n==0)continue;
        if(size+n>16L*1024*1024)throw new Exception("File exceeds 16 MiB inspection limit");
        if(size<head.length)System.arraycopy(buf,0,head,(int)size,(int)Math.min(n,head.length-size));
        // Legacy U-Boot uImage payload begins at offset 64.
        long from=Math.max(size,64),to=size+n;
        if(to>from)payloadCrc.update(buf,(int)(from-size),(int)(to-from));
        captured.write(buf,0,n);
        digest.update(buf,0,n);size+=n;
      }
      if(size==0)throw new Exception("Empty file");
      StringBuilder hash=new StringBuilder();
      for(byte v:digest.digest())hash.append(String.format(Locale.US,"%02x",v&255));
      report("File size: "+size+" bytes ("+String.format(Locale.US,"%.1f",size/1024.0)+" KiB)");
      report("SHA-256: "+hash);
      boolean recognized=false;
      if(size>=64&&be32(head,0)==0x27051956L){
        recognized=true;
        report("Format: legacy U-Boot uImage header (magic verified)");
        long expectedHeaderCrc=be32(head,4),expectedPayloadCrc=be32(head,24);
        byte[] header=Arrays.copyOf(head,64);
        Arrays.fill(header,4,8,(byte)0);
        CRC32 headerCrc=new CRC32();headerCrc.update(header);
        report("Header CRC32: "+(headerCrc.getValue()==expectedHeaderCrc?"PASS":"FAIL"));
        long payloadLength=be32(head,12);
        report("Declared payload: "+payloadLength+" bytes; actual: "+(size-64)+" bytes");
        report("Payload length: "+(payloadLength==size-64?"PASS":"MISMATCH"));
        report("Payload CRC32: "+(payloadLength==size-64&&payloadCrc.getValue()==expectedPayloadCrc?"PASS":"FAIL / NOT VERIFIED"));
        report(String.format(Locale.US,"Load address: 0x%08X; entry point: 0x%08X",be32(head,16),be32(head,20)));
        report("Architecture code: "+(head[29]&255)+" (2 means ARM in legacy uImage)");
        report("Image type: "+(head[30]&255)+"; compression code: "+(head[31]&255));
        report("Image name: "+ascii(head,32,32));
      }
      if(!recognized&&size>=32&&ascii(head,4,8).equals("eGON.BT0")){
        recognized=true;
        report("Format: Allwinner eGON boot0-style header (signature at offset 4)");
        report(String.format(Locale.US,"Header checksum field: 0x%08X; declared length: %d",le32(head,12),le32(head,16)));
        report("Checksum not validated; eGON header alone does not establish compatibility.");
      }
      if(!recognized&&size>=20&&(head[0]&255)==127&&head[1]=='E'&&head[2]=='L'&&head[3]=='F'){
        recognized=true;
        boolean little=(head[5]&255)==1;
        int machine=little?((head[18]&255)|((head[19]&255)<<8)):((head[18]&255)<<8|(head[19]&255));
        report("Format: ELF; class "+(head[4]&255)+"; byte order "+(little?"little endian":"other"));
        report("ELF machine ID: "+machine+(machine==40?" (ARM)":" (not ARM32)"));
        report("ELF header alone does not establish NES Classic compatibility.");
      }
      if(!recognized&&size>=8&&ascii(head,0,8).equals("TOC0.GLH")){
        recognized=true;report("Format: Allwinner TOC0-style header signature");
        report("TOC0 structure and cryptographic validation: NOT IMPLEMENTED");
      }
      if(!recognized){
        report("Format: unknown raw binary or unsupported image format");
        report("No recognized ELF, legacy uImage, eGON.BT0 or TOC0 signature at expected offsets.");
        report("Raw U-Boot binaries can lack recognizable headers; this is not a file integrity failure.");
      }
      byte[] all=captured.toByteArray();
      report("--- v1.8 embedded signature scan (entire selected file) ---");
      scanSignature(all,"U-Boot",new byte[]{'U','-','B','o','o','t'});
      scanSignature(all,"Allwinner eGON.BT0",new byte[]{'e','G','O','N','.','B','T','0'});
      scanSignature(all,"Allwinner TOC0.GLH",new byte[]{'T','O','C','0','.','G','L','H'});
      scanSignature(all,"Legacy uImage magic",new byte[]{0x27,0x05,0x19,0x56});
      scanSignature(all,"ELF header",new byte[]{0x7f,'E','L','F'});
      report("--- v1.8 printable identification strings ---");
      StringBuilder run=new StringBuilder();int shown=0;
      for(int i=0;i<=all.length;i++){
        int c=i==all.length?0:all[i]&255;
        if(c>=32&&c<=126&&run.length()<512)run.append((char)c);
        else {
          if(run.length()>=8){
            String value=run.toString();
            String low=value.toLowerCase(Locale.US);
            if((low.contains("u-boot")||low.contains("allwinner")||low.contains("sunxi")||low.contains("nand")||low.contains("mmc")||low.contains("fel"))&&shown<12){
              report("String at ~0x"+Integer.toHexString(Math.max(0,i-run.length()))+": "+value.substring(0,Math.min(value.length(),120)));
              shown++;
            }
          }
          run.setLength(0);
        }
      }
      if(shown==0)report("No recognizable bootloader/hardware identification strings found.");
      report("String matches are clues only, not proof of file origin, CPU support or safe execution.");
      report("--- Offline storage command protocol inventory (non-executing) ---");
      report("USB FEL version/read framing: implemented and hardware-tested.");
      report("Bootloader console command transport: NOT IMPLEMENTED / NOT VALIDATED.");
      report("Flash-read command strings: descriptive only; no command packets transmitted.");
      report("Protocol encoder for storage: NOT IMPLEMENTED (requires verified specification).");
      report("--- v2.0 U-Boot candidate identity report ---");
      final String observedCandidate="3901c183b4fbabadea517b0abbbe40c86affb8c49b2268d183a6bb9a13cb3650";
      boolean priorSample=hash.toString().equals(observedCandidate)&&size==573440;
      report("Matches previously inspected 560 KiB U-Boot sample: "+(priorSample?"YES (SHA-256 and size)":"NO"));
      report("Sample identity only: matching the prior file is NOT independent provenance verification.");
      String buildMarker="U-Boot 2011.09-rc1-00000-g1352b18-dirty";
      String markerText=new String(all,StandardCharsets.ISO_8859_1);
      report("Embedded U-Boot 2011.09 build marker: "+(markerText.contains(buildMarker)?"PRESENT":"NOT FOUND"));
      boolean flashRead=markerText.contains("sunxi_flash phy_read");
      boolean nand=markerText.contains("setargs_nand");
      boolean mmc=markerText.contains("setargs_mmc");
      report("Flash read command string: "+(flashRead?"PRESENT":"NOT FOUND"));
      report("NAND boot string: "+(nand?"PRESENT":"NOT FOUND")+"; MMC boot string: "+(mmc?"PRESENT":"NOT FOUND"));
      report("Command strings do not demonstrate callable interfaces or storage hardware compatibility.");
      report("Trusted upstream binary comparison: NOT PERFORMED (no independently authenticated reference)");
      report("Bootloader review / read-only command safety: NOT VERIFIED");
      report("--- v2.1 storage discovery decision ---");
      report("Storage transport driver: NOT IMPLEMENTED");
      report("Verified console-specific loader: NOT AVAILABLE");
      report("Read-only storage identification: BLOCKED");
      report("No capacity, partition table, NAND ID or eMMC ID was read from console.");
      report("Decision: FAIL CLOSED; no loader upload or execution.");
      report("--- v1.9 loader / storage readiness ---");
      report("File integrity fingerprint: AVAILABLE (SHA-256 above)");
      report("Known-good loader hash comparison: NOT AVAILABLE (no vetted allowlist)");
      report("Storage access protocol: NOT IMPLEMENTED");
      report("Storage controller / partition discovery: NOT ATTEMPTED");
      report("Readiness: BLOCKED pending independent compatibility validation.");
      report("NES Classic compatibility: NOT VERIFIED (format checks are not a hardware allowlist)");
      report("Loader execution: DISABLED; internal storage access: NOT IMPLEMENTED");
    }catch(Exception e){report("File analysis failed: "+e.getMessage());}
  }
  void scanSignature(byte[] data,String label,byte[] needle){
    int first=-1,count=0;
    for(int i=0;i<=data.length-needle.length;i++){
      boolean match=true;
      for(int j=0;j<needle.length;j++)if(data[i+j]!=needle[j]){match=false;break;}
      if(match){if(first<0)first=i;count++;}
    }
    report(label+": "+(count==0?"not found":"found "+count+" occurrence(s), first offset 0x"+Integer.toHexString(first)));
  }
  void showBackupRequirements(){
    new AlertDialog.Builder(this).setTitle("Firmware backup: not yet supported")
      .setMessage("The FEL USB connection is verified, but internal storage access, partition discovery, backup integrity verification, and recovery are not implemented. No firmware writes will be attempted.")
      .setPositiveButton("OK",null).show();
  }
  Button button(LinearLayout root,String label,View.OnClickListener action) {
    Button b=new Button(this); b.setText(label); b.setAllCaps(false); b.setOnClickListener(action); root.addView(b); return b;
  }
  void append(String s){log.append(s+"\n");}
  void scan() {
    device=null;
    for(UsbDevice d:manager.getDeviceList().values()){
      append(String.format(Locale.US,"Found %04X:%04X",d.getVendorId(),d.getProductId()));
      if(d.getVendorId()==VID && d.getProductId()==PID) device=d;
    }
    boolean found=device!=null, allowed=found&&manager.hasPermission(device);
    status.setText(found?"FEL 1F3A:EFE8 detected. Permission: "+(allowed?"Granted":"Needed"):"FEL device not found. Connect USB OTG.");
    permissionButton.setEnabled(found&&!allowed);probeButton.setEnabled(allowed);
  }
  void request(){
    if(device==null)return;
    Intent intent=new Intent(PERMISSION).setPackage(getPackageName());
    PendingIntent pi=PendingIntent.getBroadcast(this,0,intent,PendingIntent.FLAG_IMMUTABLE);
    manager.requestPermission(device,pi);
  }
  void probe(){
    if(device==null||!manager.hasPermission(device)){append("No USB permission");return;}
    UsbInterface iface=null;
    for(int n=0;n<device.getInterfaceCount();n++){
      UsbInterface x=device.getInterface(n);
      boolean in=false,out=false;
      for(int k=0;k<x.getEndpointCount();k++){
        UsbEndpoint ep=x.getEndpoint(k);
        if(ep.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK){
          if(ep.getDirection()==UsbConstants.USB_DIR_IN)in=true;
          if(ep.getDirection()==UsbConstants.USB_DIR_OUT)out=true;
        }
      }
      if(in&&out){iface=x;break;}
    }
    if(iface==null){append("FAIL: No bulk IN/OUT interface");return;}
    UsbDeviceConnection connection=manager.openDevice(device);
    if(connection==null){append("FAIL: openDevice returned null");return;}
    try {
      boolean claimed=connection.claimInterface(iface,false);
      append(claimed?"PASS: bulk interface claimed successfully":"FAIL: claimInterface returned false");
      if(claimed)connection.releaseInterface(iface);
    } finally {connection.close();}
  }

  static byte[] usbHeader(int type,int length){
    ByteBuffer b=ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
    b.put(new byte[]{'A','W','U','C',0,0,0,0});
    b.putInt(length); b.putInt(0x0c000000); b.putShort((short)type); b.putInt(length);
    return b.array();
  }
  void send(UsbDeviceConnection c,UsbEndpoint ep,byte[] bytes)throws Exception{
    int offset=0;
    while(offset<bytes.length){
      int n=c.bulkTransfer(ep,bytes,offset,bytes.length-offset,4000);
      if(n<=0)throw new Exception("USB OUT failed at "+offset+" (result "+n+")");
      offset+=n;
    }
  }
  byte[] recv(UsbDeviceConnection c,UsbEndpoint ep,int len)throws Exception{
    byte[] data=new byte[len];int offset=0;
    while(offset<len){
      int n=c.bulkTransfer(ep,data,offset,len-offset,4000);
      if(n<=0)throw new Exception("USB IN failed at "+offset+" (result "+n+")");
      offset+=n;
    }
    return data;
  }
  void usbStatus(UsbDeviceConnection c,UsbEndpoint in)throws Exception{
    byte[] status=recv(c,in,13);
    if(status[0]!='A'||status[1]!='W'||status[2]!='U'||status[3]!='S')
      throw new Exception("Invalid USB status header: "+hex(status));
  }
  void felWriteRequest(UsbDeviceConnection c,UsbEndpoint out,UsbEndpoint in)throws Exception{
    ByteBuffer request=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
    request.putInt(1); // AW_FEL_VERSION; no memory access or execution
    send(c,out,usbHeader(0x12,16));
    send(c,out,request.array());
    usbStatus(c,in);
  }
  byte[] felRead(UsbDeviceConnection c,UsbEndpoint out,UsbEndpoint in,int size)throws Exception{
    send(c,out,usbHeader(0x11,size));
    byte[] data=recv(c,in,size);
    usbStatus(c,in);
    return data;
  }
  String hex(byte[] b){
    StringBuilder s=new StringBuilder();
    for(byte x:b)s.append(String.format(Locale.US,"%02X ",x&255));
    return s.toString().trim();
  }
  void readFelVersion(int repeats){
    if(device==null||!manager.hasPermission(device)){append("FAIL: FEL device or USB permission missing");return;}
    UsbInterface iface=null;UsbEndpoint in=null,out=null;
    for(int i=0;i<device.getInterfaceCount();i++){
      UsbInterface it=device.getInterface(i);UsbEndpoint a=null,b=null;
      for(int k=0;k<it.getEndpointCount();k++){
        UsbEndpoint ep=it.getEndpoint(k);
        if(ep.getType()!=UsbConstants.USB_ENDPOINT_XFER_BULK)continue;
        if(ep.getDirection()==UsbConstants.USB_DIR_IN)a=ep;
        else b=ep;
      }
      if(a!=null&&b!=null){iface=it;in=a;out=b;break;}
    }
    if(iface==null){append("FAIL: No FEL bulk endpoints");return;}
    UsbDeviceConnection c=manager.openDevice(device);
    if(c==null){append("FAIL: Unable to open device");return;}
    try{
      if(!c.claimInterface(iface,false)){append("FAIL: Could not claim interface");return;}
      append("FEL v0.3 read-only test: "+repeats+" request(s)");
      byte[] baseline=null;
      for(int test=1;test<=repeats;test++){
        long start=System.nanoTime();
        felWriteRequest(c,out,in);
        byte[] version=felRead(c,out,in,32);
        byte[] status=felRead(c,out,in,8);
        String sig=new String(version,0,8,StandardCharsets.US_ASCII);
        if(!"AWUSBFEX".equals(sig))throw new Exception("Invalid version signature on read "+test+": "+sig);
        if(status[0]!=(byte)0xff||status[1]!=(byte)0xff)
          throw new Exception("Unexpected FEL status on read "+test+": "+hex(status));
        if(baseline!=null&&!Arrays.equals(baseline,version))
          throw new Exception("Version changed between reads at test "+test);
        baseline=version;
        int soc=(ByteBuffer.wrap(version).order(ByteOrder.LITTLE_ENDIAN).getInt(8)>>>8)&0xffff;
        int protocol=ByteBuffer.wrap(version).order(ByteOrder.LITTLE_ENDIAN).getShort(16)&0xffff;
        long ms=(System.nanoTime()-start)/1000000;
        append(String.format(Locale.US,"PASS %d/%d: SoC 0x%04X, protocol %d, %d ms",test,repeats,soc,protocol,ms));
      }
      append("Version bytes: "+hex(baseline));
      append("PASS: "+repeats+"/"+repeats+" consistent FEL version responses; no memory writes.");
    }catch(Exception e){append("FAIL: "+e.getMessage());}
    finally{c.releaseInterface(iface);c.close();}
  }

  void report(String message){runOnUiThread(()->append(message));}
  void readScratchRam(int repeats){
    if(device==null||!manager.hasPermission(device)){report("FAIL: FEL device or USB permission missing");return;}
    UsbInterface iface=null;UsbEndpoint in=null,out=null;
    for(int i=0;i<device.getInterfaceCount();i++){
      UsbInterface it=device.getInterface(i);UsbEndpoint a=null,b=null;
      for(int k=0;k<it.getEndpointCount();k++){
        UsbEndpoint ep=it.getEndpoint(k);
        if(ep.getType()!=UsbConstants.USB_ENDPOINT_XFER_BULK)continue;
        if(ep.getDirection()==UsbConstants.USB_DIR_IN)a=ep;
        else b=ep;
      }
      if(a!=null&&b!=null){iface=it;in=a;out=b;break;}
    }
    if(iface==null){report("FAIL: No bulk endpoints");return;}
    UsbDeviceConnection c=manager.openDevice(device);
    if(c==null){report("FAIL: Cannot open USB device");return;}
    boolean claimed=false;
    try{
      claimed=c.claimInterface(iface,false);
      if(!claimed)throw new Exception("Cannot claim FEL interface");
      // Query the boot ROM first, and derive the scratch address it reports.
      felWriteRequest(c,out,in);
      byte[] version=felRead(c,out,in,32);
      byte[] versionStatus=felRead(c,out,in,8);
      if(!"AWUSBFEX".equals(new String(version,0,8,StandardCharsets.US_ASCII)))
        throw new Exception("Invalid FEL version signature");
      if(versionStatus[0]!=(byte)0xff||versionStatus[1]!=(byte)0xff)
        throw new Exception("Unexpected version status: "+hex(versionStatus));
      long scratch=ByteBuffer.wrap(version).order(ByteOrder.LITTLE_ENDIAN).getInt(20)&0xffffffffL;
      if(scratch==0||scratch>0x100000||scratch%4!=0)
        throw new Exception("Scratch address outside conservative range: 0x"+Long.toHexString(scratch));
      report(String.format(Locale.US,"FEL v0.5: %d read-only RAM read(s) at 0x%08X",repeats,scratch));
      byte[] baseline=null;
      long totalNs=0;
      for(int test=1;test<=repeats;test++){
        ByteBuffer req=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        req.putInt(0x103); // AW_FEL_1_READ, never write or execute
        req.putInt((int)scratch);
        req.putInt(16);
        req.putInt(0);
        long started=System.nanoTime();
        send(c,out,usbHeader(0x12,16));
        send(c,out,req.array());
        usbStatus(c,in);
        byte[] data=felRead(c,out,in,16);
        byte[] status=felRead(c,out,in,8);
        long elapsed=System.nanoTime()-started;
        totalNs+=elapsed;
        if(status[0]!=(byte)0xff||status[1]!=(byte)0xff)
          throw new Exception("Unexpected read status on "+test+": "+hex(status));
        if(baseline!=null&&!Arrays.equals(baseline,data))
          throw new Exception("RAM contents differed on read "+test+"; memory may be changing");
        baseline=data;
        report(String.format(Locale.US,"PASS RAM %d/%d: %.2f ms",test,repeats,elapsed/1000000.0));
      }
      report("RAM bytes: "+hex(baseline));
      report(String.format(Locale.US,"PASS: %d/%d matching RAM reads; average %.2f ms; no memory writes.",repeats,repeats,totalNs/1000000.0/repeats));
    }catch(Exception e){report("FAIL: FEL RAM read: "+e.getMessage());}
    finally{if(claimed)c.releaseInterface(iface);c.close();}
  }

  void liveLoaderMemoryReadV44(){
    report("=== v4.4 LIVE FEL loader-memory preflight (READ-ONLY) ===");
    UsbDevice d=device;
    if(d==null||!manager.hasPermission(d)){report("BLOCKED: USB permission required");return;}
    UsbInterface iface=null;UsbEndpoint in=null,out=null;
    for(int i=0;i<d.getInterfaceCount();i++){
      UsbInterface it=d.getInterface(i);UsbEndpoint a=null,b=null;
      for(int k=0;k<it.getEndpointCount();k++){
        UsbEndpoint ep=it.getEndpoint(k);
        if(ep.getType()!=UsbConstants.USB_ENDPOINT_XFER_BULK)continue;
        if(ep.getAddress()==0x82)a=ep;
        if(ep.getAddress()==0x01)b=ep;
      }
      if(a!=null&&b!=null){iface=it;in=a;out=b;break;}
    }
    if(iface==null){report("BLOCKED: expected FEL USB endpoints absent");return;}
    UsbDeviceConnection c=manager.openDevice(d);
    if(c==null){report("FAIL: could not open FEL USB connection");return;}
    boolean claimed=false;
    try{
      claimed=c.claimInterface(iface,false);
      if(!claimed)throw new Exception("USB interface claim failed");
      felWriteRequest(c,out,in);
      byte[] version=felRead(c,out,in,32);
      byte[] vs=felRead(c,out,in,8);
      if(!"AWUSBFEX".equals(new String(version,0,8,StandardCharsets.US_ASCII))||
          vs[0]!=(byte)0xff||vs[1]!=(byte)0xff)
        throw new Exception("FEL ROM identity or status mismatch");
      int soc=(ByteBuffer.wrap(version).order(ByteOrder.LITTLE_ENDIAN).getInt(8)>>>8)&0xffff;
      if(soc!=0x1667)throw new Exception("Unexpected SoC 0x"+Integer.toHexString(soc));
      byte[] first=null;
      for(int repeat=1;repeat<=2;repeat++){
        byte[] req=felMessageV40(0x103,0x2000,128);
        send(c,out,usbHeader(0x12,req.length));
        send(c,out,req);
        usbStatus(c,in);
        byte[] current=felRead(c,out,in,128);
        byte[] status=felRead(c,out,in,8);
        if(status[0]!=(byte)0xff||status[1]!=(byte)0xff)
          throw new Exception("FEL memory-read status invalid");
        if(first!=null&&!Arrays.equals(first,current))
          throw new Exception("Loader SRAM target changed between reads");
        first=current;
      }
      report("PASS: live 128-byte SRAM read at FES1 destination 0x00002000, repeated 2/2");
      report("SHA-256 of observed SRAM: "+hexDigest(MessageDigest.getInstance("SHA-256").digest(first)));
      report("No memory writes, DRAM initialization, U-Boot execution or flash reads performed.");
    }catch(Exception e){report("FAIL: v4.4 loader-memory preflight: "+e.getMessage());}
    finally{if(claimed)c.releaseInterface(iface);c.close();}
  }
  static final String FES1_BLOB="a64371dc69714e78132af03bac874278b91bb87e";
  byte[] verifiedFes1V45()throws Exception{
    byte[] data;
    try(InputStream in=getAssets().open("fes1.bin");ByteArrayOutputStream out=new ByteArrayOutputStream()){
      byte[] buf=new byte[8192];int n;
      while((n=in.read(buf))!=-1){if(n>0)out.write(buf,0,n);if(out.size()>12624)throw new Exception("Oversized loader");}
      data=out.toByteArray();
    }
    if(data.length!=12624)throw new Exception("Unexpected FES1 length");
    MessageDigest md=MessageDigest.getInstance("SHA-1");
    md.update(("blob "+data.length+((char)0)).getBytes(StandardCharsets.US_ASCII));
    md.update(data);
    if(!FES1_BLOB.equals(hexDigest(md.digest())))throw new Exception("FES1 integrity check failed");
    return data;
  }
  byte[] loaderReadV45(UsbDeviceConnection c,UsbEndpoint out,UsbEndpoint in,long address,int len)throws Exception{
    if(len<=0||len>65536||(len&3)!=0)throw new Exception("Invalid read size");
    byte[] req=felMessageV40(0x103,address,len);
    send(c,out,usbHeader(0x12,16));send(c,out,req);usbStatus(c,in);
    byte[] result=felRead(c,out,in,len);
    byte[] st=felRead(c,out,in,8);
    if(st[0]!=(byte)0xff||st[1]!=(byte)0xff)throw new Exception("FEL upload status invalid");
    return result;
  }
  void loaderWriteV45(UsbDeviceConnection c,UsbEndpoint out,UsbEndpoint in,long address,byte[] data)throws Exception{
    if(data.length==0||(data.length&3)!=0||data.length>65536)throw new Exception("Invalid write size");
    byte[] req=felMessageV40(0x101,address,data.length);
    send(c,out,usbHeader(0x12,16));send(c,out,req);usbStatus(c,in);
    send(c,out,usbHeader(0x12,data.length));send(c,out,data);usbStatus(c,in);
    byte[] st=felRead(c,out,in,8);
    if(st[0]!=(byte)0xff||st[1]!=(byte)0xff)throw new Exception("FEL download status invalid");
  }
  void loaderExecuteV45(UsbDeviceConnection c,UsbEndpoint out,UsbEndpoint in,long address)throws Exception{
    byte[] req=felMessageV40(0x102,address,0);
    send(c,out,usbHeader(0x12,16));send(c,out,req);usbStatus(c,in);
    byte[] st=felRead(c,out,in,8);
    if(st[0]!=(byte)0xff||st[1]!=(byte)0xff)throw new Exception("FEL execute status invalid");
  }
  void confirmLoaderV45(){
    new AlertDialog.Builder(this).setTitle("Execute FES1 loader in NES Classic RAM?")
      .setMessage("Advanced experimental operation. Writes the verified Hakchi FES1 binary to console SRAM and executes it to initialize DRAM. This does NOT write internal flash, create a firmware backup, detect Hakchi, or manage games. The console may freeze or disconnect; power-cycle into FEL mode to recover. Proceed only if you accept that risk.")
      .setNegativeButton("Cancel",null)
      .setPositiveButton("Execute FES1", (dialog,which)->new Thread(()->runLoaderV45()).start()).show();
  }
  void runLoaderV45(){
    report("=== EXPERIMENTAL FES1 SRAM LOAD AND EXECUTION ===");
    UsbDevice d=device;
    if(d==null||!manager.hasPermission(d)){report("BLOCKED: connect FEL and grant USB permission");return;}
    UsbInterface iface=null;UsbEndpoint in=null,out=null;
    for(int i=0;i<d.getInterfaceCount();i++){
      UsbInterface it=d.getInterface(i);UsbEndpoint a=null,b=null;
      for(int k=0;k<it.getEndpointCount();k++){
        UsbEndpoint ep=it.getEndpoint(k);
        if(ep.getType()!=UsbConstants.USB_ENDPOINT_XFER_BULK)continue;
        if(ep.getAddress()==0x82)a=ep;
        if(ep.getAddress()==0x01)b=ep;
      }
      if(a!=null&&b!=null){iface=it;in=a;out=b;break;}
    }
    if(iface==null){report("BLOCKED: FEL endpoints 0x01/0x82 not found");return;}
    UsbDeviceConnection c=manager.openDevice(d);
    if(c==null){report("FAIL: cannot open USB device");return;}
    boolean claimed=false;
    try{
      byte[] loader=verifiedFes1V45();
      claimed=c.claimInterface(iface,false);
      if(!claimed)throw new Exception("USB claim failed");
      felWriteRequest(c,out,in);
      byte[] version=felRead(c,out,in,32),st=felRead(c,out,in,8);
      if(!"AWUSBFEX".equals(new String(version,0,8,StandardCharsets.US_ASCII))||
          st[0]!=(byte)0xff||st[1]!=(byte)0xff)throw new Exception("FEL identity mismatch");
      int soc=(ByteBuffer.wrap(version).order(ByteOrder.LITTLE_ENDIAN).getInt(8)>>>8)&0xffff;
      if(soc!=0x1667)throw new Exception("Unsupported SoC 0x"+Integer.toHexString(soc));
      byte[] tail=loaderReadV45(c,out,in,0x2000+loader.length-128,128);
      if(Arrays.equals(tail,Arrays.copyOfRange(loader,loader.length-128,loader.length))){
        report("PASS: FES1 loader signature already present in SRAM; execution skipped");
        return;
      }
      report("Verified original FES1; writing "+loader.length+" bytes to SRAM only");
      int offset=0;
      while(offset<loader.length){
        int n=Math.min(4096,loader.length-offset);
        byte[] chunk=Arrays.copyOfRange(loader,offset,offset+n);
        loaderWriteV45(c,out,in,0x2000+offset,chunk);
        offset+=n;
      }
      report("FES1 SRAM upload complete; verifying entire image by readback");
      offset=0;
      while(offset<loader.length){
        int n=Math.min(4096,loader.length-offset);
        byte[] read=loaderReadV45(c,out,in,0x2000+offset,n);
        for(int i=0;i<n;i++)if(read[i]!=loader[offset+i])
          throw new Exception("SRAM verification mismatch at offset "+(offset+i));
        offset+=n;
      }
      report("PASS: entire FES1 image verified in SRAM");
      report("Executing FES1 at 0x00002000 (no flash write)");
      loaderExecuteV45(c,out,in,0x2000);
      report("FES1 execution command acknowledged; DRAM initialization NOT YET VERIFIED");
    }catch(Exception e){report("FAIL: FES1 loader stage: "+e.getMessage());}
    finally{if(claimed)c.releaseInterface(iface);c.close();}
  }
  volatile boolean ubootStagedV47=false;
  void confirmUbootStageV47(){
    if(!dramVerifiedV46){report("BLOCKED: Stage 3 must pass first");return;}
    new AlertDialog.Builder(this).setTitle("Stage 4: Upload U-Boot to DRAM?")
      .setMessage("This experimental step writes the verified Hakchi U-Boot image into volatile DRAM and reads it back for verification. It will NOT execute U-Boot or touch internal flash. A failed transfer may require power-cycling the NES Classic. Continue?")
      .setNegativeButton("Cancel",null)
      .setPositiveButton("Upload and verify", (dialog,which)->new Thread(()->runUbootStageV47()).start()).show();
  }
  void runUbootStageV47(){
    report("=== STAGE 4: LIVE U-BOOT DRAM UPLOAD AND VERIFY ===");
    ubootStagedV47=false;
    if(!dramVerifiedV46){report("BLOCKED: Stage 3 not verified");return;}
    UsbDevice d=device;
    if(d==null||!manager.hasPermission(d)){report("BLOCKED: FEL USB permission missing");return;}
    UsbInterface iface=null;UsbEndpoint in=null,out=null;
    for(int i=0;i<d.getInterfaceCount();i++){
      UsbInterface it=d.getInterface(i);UsbEndpoint a=null,b=null;
      for(int k=0;k<it.getEndpointCount();k++){
        UsbEndpoint ep=it.getEndpoint(k);
        if(ep.getType()!=UsbConstants.USB_ENDPOINT_XFER_BULK)continue;
        if(ep.getAddress()==0x82)a=ep;
        if(ep.getAddress()==0x01)b=ep;
      }
      if(a!=null&&b!=null){iface=it;in=a;out=b;break;}
    }
    if(iface==null){report("BLOCKED: expected FEL endpoints not found");return;}
    UsbDeviceConnection c=manager.openDevice(d);
    if(c==null){report("FAIL: USB open failed");return;}
    boolean claimed=false;
    try{
      byte[] data;
      try(InputStream input=getAssets().open("uboot.bin");ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
        byte[] buf=new byte[8192];int n;
        while((n=input.read(buf))!=-1){if(n>0)bytes.write(buf,0,n);if(bytes.size()>573440)throw new Exception("Oversized U-Boot");}
        data=bytes.toByteArray();
      }
      MessageDigest md=MessageDigest.getInstance("SHA-1");
      md.update(("blob "+data.length+((char)0)).getBytes(StandardCharsets.US_ASCII));md.update(data);
      if(data.length!=573440||!UBOOT_UPSTREAM_GIT_SHA1.equals(hexDigest(md.digest())))
        throw new Exception("U-Boot identity mismatch");
      if(bootCommandOffsetV40(data)<0)throw new Exception("bootcmd field absent");
      claimed=c.claimInterface(iface,false);
      if(!claimed)throw new Exception("USB claim failed");
      felWriteRequest(c,out,in);
      byte[] version=felRead(c,out,in,32),st=felRead(c,out,in,8);
      if(!"AWUSBFEX".equals(new String(version,0,8,StandardCharsets.US_ASCII))||
        st[0]!=(byte)0xff||st[1]!=(byte)0xff)throw new Exception("FEL identity invalid");
      int soc=(ByteBuffer.wrap(version).order(ByteOrder.LITTLE_ENDIAN).getInt(8)>>>8)&0xffff;
      if(soc!=0x1667)throw new Exception("Unexpected SoC");
      report("Uploading verified U-Boot "+data.length+" bytes to DRAM 0x47000000");
      for(int pos=0;pos<data.length;pos+=4096){
        int n=Math.min(4096,data.length-pos);
        loaderWriteV45(c,out,in,0x47000000L+pos,Arrays.copyOfRange(data,pos,pos+n));
        if((pos/4096)%32==0)report("U-Boot upload "+(pos+n)+"/"+data.length);
      }
      report("Upload complete; checking all bytes against DRAM readback");
      for(int pos=0;pos<data.length;pos+=4096){
        int n=Math.min(4096,data.length-pos);
        byte[] read=loaderReadV45(c,out,in,0x47000000L+pos,n);
        for(int j=0;j<n;j++)if(read[j]!=data[pos+j])
          throw new Exception("U-Boot DRAM mismatch at byte "+(pos+j));
      }
      ubootStagedV47=true;
      report("PASS: U-Boot image fully uploaded and byte-for-byte verified in volatile DRAM");
      report("NOT EXECUTED: U-Boot boot, USB reconnect, flash read and backup remain disabled");
    }catch(Exception e){report("FAIL: Stage 4 U-Boot upload: "+e.getMessage());}
    finally{if(claimed)c.releaseInterface(iface);c.close();}
  }
  volatile boolean dramVerifiedV46=false;
  void runStage3V46(){
    report("=== STAGE 3: LIVE DRAM READ VERIFICATION ===");
    dramVerifiedV46=false;
    UsbDevice d=device;
    if(d==null||!manager.hasPermission(d)){report("BLOCKED: connect FEL console and grant USB permission");return;}
    UsbInterface iface=null;UsbEndpoint in=null,out=null;
    for(int i=0;i<d.getInterfaceCount();i++){
      UsbInterface it=d.getInterface(i);UsbEndpoint a=null,b=null;
      for(int k=0;k<it.getEndpointCount();k++){
        UsbEndpoint ep=it.getEndpoint(k);
        if(ep.getType()!=UsbConstants.USB_ENDPOINT_XFER_BULK)continue;
        if(ep.getAddress()==0x82)a=ep;
        if(ep.getAddress()==0x01)b=ep;
      }
      if(a!=null&&b!=null){iface=it;in=a;out=b;break;}
    }
    if(iface==null){report("BLOCKED: FEL endpoints missing");return;}
    UsbDeviceConnection c=manager.openDevice(d);
    if(c==null){report("FAIL: unable to open FEL USB connection");return;}
    boolean claimed=false;
    try{
      claimed=c.claimInterface(iface,false);
      if(!claimed)throw new Exception("USB claim failed");
      felWriteRequest(c,out,in);
      byte[] version=felRead(c,out,in,32),st=felRead(c,out,in,8);
      if(!"AWUSBFEX".equals(new String(version,0,8,StandardCharsets.US_ASCII))||
        st[0]!=(byte)0xff||st[1]!=(byte)0xff)throw new Exception("FEL version/status invalid");
      int soc=(ByteBuffer.wrap(version).order(ByteOrder.LITTLE_ENDIAN).getInt(8)>>>8)&0xffff;
      if(soc!=0x1667)throw new Exception("Unexpected SoC 0x"+Integer.toHexString(soc));
      byte[] fes1=verifiedFes1V45();
      byte[] tail=loaderReadV45(c,out,in,0x2000+fes1.length-128,128);
      if(!Arrays.equals(tail,Arrays.copyOfRange(fes1,fes1.length-128,fes1.length)))
        throw new Exception("FES1 loader tail not present; run FES1 stage first");
      // Read an unused DRAM test window; reads alone do not establish DRAM stability.
      byte[] first=loaderReadV45(c,out,in,0x47000000L,128);
      byte[] second=loaderReadV45(c,out,in,0x47000000L,128);
      if(!Arrays.equals(first,second))throw new Exception("DRAM readback unstable");
      // Detect a common unmapped/bus-fill pattern; this remains a provisional test.
      boolean uniform=true;
      for(int i=1;i<first.length;i++)if(first[i]!=first[0]){uniform=false;break;}
      if(uniform)throw new Exception("DRAM returned a uniform byte pattern; cannot confirm initialization");
      dramVerifiedV46=true;
      report("PASS: 2 matching DRAM reads at 0x47000000 (128 bytes)");
      report("DRAM SHA-256: "+hexDigest(MessageDigest.getInstance("SHA-256").digest(first)));
      report("PROVISIONAL: stable reads do not prove DRAM writes or full memory integrity");
    }catch(Exception e){report("NOT VERIFIED: Stage 3: "+e.getMessage());}
    finally{if(claimed)c.releaseInterface(iface);c.close();}
  }
  void runStage4V46(){
    report("=== STAGE 4: U-BOOT SAFETY GATE ===");
    if(!dramVerifiedV46){report("BLOCKED: Stage 3 DRAM verification must pass this session");return;}
    try{
      byte[] data;
      try(InputStream in=getAssets().open("uboot.bin");ByteArrayOutputStream b=new ByteArrayOutputStream()){
        byte[] buf=new byte[8192];int n;
        while((n=in.read(buf))!=-1){if(n>0)b.write(buf,0,n);if(b.size()>573440)throw new Exception("Oversized U-Boot");}
        data=b.toByteArray();
      }
      MessageDigest md=MessageDigest.getInstance("SHA-1");
      md.update(("blob "+data.length+((char)0)).getBytes(StandardCharsets.US_ASCII));md.update(data);
      if(data.length!=573440||!UBOOT_UPSTREAM_GIT_SHA1.equals(hexDigest(md.digest())))
        throw new Exception("U-Boot identity failed");
      int offset=bootCommandOffsetV40(data);
      if(offset<0)throw new Exception("U-Boot bootcmd missing");
      report("PASS: verified U-Boot binary and bootcmd offset "+offset);
      report("NOT IMPLEMENTED: controlled U-Boot upload, execution, USB reconnect and recovery validation");
      report("No U-Boot executed; no flash read or write attempted");
    }catch(Exception e){report("FAIL: Stage 4 preflight: "+e.getMessage());}
  }
  void runStage5V46(){
    report("=== STAGE 5: STORAGE / BACKUP / HAKCHI CAPABILITY GATE ===");
    report("NOT IMPLEMENTED: live flash read; firmware backup; Hakchi detection; game management");
    report(ubootStagedV47?"PASS: U-Boot staged in DRAM; execution/reconnect not implemented":"BLOCKED: Stage 4 U-Boot staging not verified");
    report("BLOCKED: live flash reads require controlled U-Boot execution and verified reconnect");
    report("No firmware extraction, storage write, or game modification attempted");
  }
  void identifyConsole(){
    UsbDevice d=device;
    if(d==null||!manager.hasPermission(d)){report("FAIL: FEL device or permission missing");return;}
    report("=== FEL Probe v0.6 identification report ===");
    report(String.format(Locale.US,"USB VID:PID %04X:%04X",d.getVendorId(),d.getProductId()));
    report("USB interfaces: "+d.getInterfaceCount());
    UsbInterface iface=null;UsbEndpoint in=null,out=null;
    for(int i=0;i<d.getInterfaceCount();i++){
      UsbInterface it=d.getInterface(i);
      report(String.format(Locale.US,"Interface %d: class 0x%02X, endpoints %d",i,it.getInterfaceClass(),it.getEndpointCount()));
      UsbEndpoint a=null,b=null;
      for(int k=0;k<it.getEndpointCount();k++){
        UsbEndpoint ep=it.getEndpoint(k);
        report(String.format(Locale.US," Endpoint 0x%02X, type %d, max packet %d",ep.getAddress(),ep.getType(),ep.getMaxPacketSize()));
        if(ep.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK){
          if(ep.getDirection()==UsbConstants.USB_DIR_IN)a=ep;else b=ep;
        }
      }
      if(iface==null&&a!=null&&b!=null){iface=it;in=a;out=b;}
    }
    if(iface==null){report("FAIL: No FEL bulk interface");return;}
    UsbDeviceConnection c=manager.openDevice(d);
    if(c==null){report("FAIL: USB open failed");return;}
    boolean claimed=false;
    try{
      claimed=c.claimInterface(iface,false);
      if(!claimed)throw new Exception("Cannot claim interface");
      felWriteRequest(c,out,in);
      byte[] version=felRead(c,out,in,32);
      byte[] felStatus=felRead(c,out,in,8);
      if(!"AWUSBFEX".equals(new String(version,0,8,StandardCharsets.US_ASCII)))
        throw new Exception("Unexpected FEL signature");
      if(felStatus[0]!=(byte)0xff||felStatus[1]!=(byte)0xff)
        throw new Exception("Unexpected FEL status: "+hex(felStatus));
      ByteBuffer b=ByteBuffer.wrap(version).order(ByteOrder.LITTLE_ENDIAN);
      int soc=(b.getInt(8)>>>8)&0xffff;
      int protocol=b.getShort(16)&0xffff;
      long scratch=b.getInt(20)&0xffffffffL;
      report("FEL signature: AWUSBFEX");
      report(String.format(Locale.US,"SoC ID: 0x%04X",soc));
      report("FEL protocol: "+protocol);
      report(String.format(Locale.US,"FEL scratch address: 0x%08X",scratch));
      report("SoC family: "+(soc==0x1667?"Allwinner R16/A33 family (expected NES Classic hardware)":"Not recognized by this app"));
      report("Firmware / existing Hakchi installation: UNKNOWN (not available from FEL version)");
      report("No firmware, storage or kernel modifications performed.");
      report("PASS: Console FEL identification report complete");
    }catch(Exception e){report("FAIL: Identification: "+e.getMessage());}
    finally{if(claimed)c.releaseInterface(iface);c.close();}
  }

  void backupReadiness(){
    UsbDevice d=device;
    report("=== FEL Probe v0.7: backup readiness ===");
    if(d==null){report("BLOCKED: FEL USB device not detected");return;}
    if(!manager.hasPermission(d)){report("BLOCKED: USB permission required");return;}
    report(String.format(Locale.US,"USB device: %04X:%04X",d.getVendorId(),d.getProductId()));
    UsbInterface iface=null;UsbEndpoint in=null,out=null;
    for(int i=0;i<d.getInterfaceCount();i++){
      UsbInterface it=d.getInterface(i);UsbEndpoint a=null,b=null;
      for(int k=0;k<it.getEndpointCount();k++){
        UsbEndpoint ep=it.getEndpoint(k);
        if(ep.getType()!=UsbConstants.USB_ENDPOINT_XFER_BULK)continue;
        if(ep.getDirection()==UsbConstants.USB_DIR_IN)a=ep;else b=ep;
      }
      if(a!=null&&b!=null){iface=it;in=a;out=b;break;}
    }
    if(iface==null){report("BLOCKED: Missing bulk endpoints");return;}
    UsbDeviceConnection c=manager.openDevice(d);
    if(c==null){report("BLOCKED: Cannot open FEL USB device");return;}
    boolean claimed=false;
    try{
      claimed=c.claimInterface(iface,false);
      if(!claimed)throw new Exception("Cannot claim interface");
      felWriteRequest(c,out,in);
      byte[] version=felRead(c,out,in,32);
      byte[] status=felRead(c,out,in,8);
      if(!"AWUSBFEX".equals(new String(version,0,8,StandardCharsets.US_ASCII)))
        throw new Exception("FEL signature mismatch");
      if(status[0]!=(byte)0xff||status[1]!=(byte)0xff)
        throw new Exception("FEL status mismatch");
      ByteBuffer b=ByteBuffer.wrap(version).order(ByteOrder.LITTLE_ENDIAN);
      int soc=(b.getInt(8)>>>8)&0xffff;
      int protocol=b.getShort(16)&0xffff;
      report(String.format(Locale.US,"PASS: FEL responding, SoC 0x%04X, protocol %d",soc,protocol));
      report("USB transport: AVAILABLE");
      report("FEL boot ROM: AVAILABLE");
      report("Internal flash / NAND or eMMC access: NOT IMPLEMENTED");
      report("Partition map discovery: NOT IMPLEMENTED");
      report("Existing Hakchi installation detection: NOT IMPLEMENTED");
      report("Backup file export and integrity validation: NOT IMPLEMENTED");
      report("Restore / recovery procedure: NOT VERIFIED");
      report("ASSESSMENT: NOT READY for firmware backup or flashing");
      report("No firmware or memory writes performed.");
    }catch(Exception e){report("BLOCKED: "+e.getMessage());}
    finally{if(claimed)c.releaseInterface(iface);c.close();}
  }

  static final int EXPORT_DIAGNOSTICS=7008;
  void exportDiagnosticJson(){
    Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
    i.addCategory(Intent.CATEGORY_OPENABLE);
    i.setType("application/json");
    i.putExtra(Intent.EXTRA_TITLE,"nes-fel-diagnostics.json");
    startActivityForResult(i,EXPORT_DIAGNOSTICS);
  }
  String jsonEscape(String s){
    return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");
  }
  @Override protected void onActivityResult(int request,int result,Intent data){
    super.onActivityResult(request,result,data);
    if(result!=RESULT_OK||data==null||data.getData()==null)return;
    if(request==INSPECT_FES1){
      Uri file=data.getData();
      append("FES1 file selected; starting offline inspection.");
      try{getContentResolver().takePersistableUriPermission(file,Intent.FLAG_GRANT_READ_URI_PERMISSION);}
      catch(Exception e){append("FES1 access may need renewal: "+e.getMessage());}
      getPreferences(MODE_PRIVATE).edit().putString("fes1_uri",file.toString()).apply();
      new Thread(()->inspectFes1(file)).start();
      return;
    }
    if(request==INSPECT_LOADER){
      Uri file=data.getData();
      try{getContentResolver().takePersistableUriPermission(file,Intent.FLAG_GRANT_READ_URI_PERMISSION);}
      catch(Exception e){append("File permission may need to be renewed later: "+e.getMessage());}
      getPreferences(MODE_PRIVATE).edit().putString("loader_uri",file.toString()).apply();
      append("Loader selected and saved for future checks.");
      if(allInOnePending){allInOnePending=false;beginAudit();}
      new Thread(()->inspectLoaderFile(file)).start();
      return;
    }
    if(request!=EXPORT_DIAGNOSTICS)return;
    Uri uri=data.getData();
    UsbDevice d=device;
    String deviceId=d==null?"not connected":String.format(Locale.US,"%04X:%04X",d.getVendorId(),d.getProductId());
    String payload="{\n"+
      "  \"report_format\": \"hakchi-fel-probe-v2.0-2.1\",\n"+
      "  \"device_id\": \""+deviceId+"\",\n"+
      "  \"usb_permission\": "+(d!=null&&manager.hasPermission(d))+",\n"+
      "  \"firmware_backup_created\": false,\n"+
      "  \"firmware_storage_access_implemented\": false,\n"+
      "  \"note\": \"Diagnostic log only; not a firmware backup\",\n"+
      "  \"diagnostic_log\": \""+jsonEscape(log.getText().toString())+"\"\n"+
      "}\n";
    try(OutputStream stream=getContentResolver().openOutputStream(uri)){
      if(stream==null)throw new Exception("Could not open destination");
      stream.write(payload.getBytes(StandardCharsets.UTF_8));
      append("PASS: Diagnostic JSON exported (not a firmware backup)");
    }catch(Exception e){append("FAIL: JSON export: "+e.getMessage());}
  }

  void runAllTests(){
    report("=== FEL Probe v2.0/2.1: COMPLETE READ-ONLY AUDIT ===");
    report("Hardware and software checks only; no storage commands or flashing.");
    protocolSelfTestsV30();
    softwarePreflight();
    if(device==null||!manager.hasPermission(device)){
      report("BLOCKED: FEL hardware tests require USB permission.");
    }else{
      fullDiagnostics();
      readScratchRam(5);
      liveLoaderMemoryReadV44();
      identifyConsole();
      backupReadiness();
    }
    verifyStorageLoaderCompatibilityGate();
    storageDiscoveryPreflight();
    storageAccessGateV19();
    storageDiscoveryGateV21();
    auditCapabilities();
    report("=== REMAINING CAPABILITIES ===");
    report("NOT IMPLEMENTED: NAND/eMMC controller driver, storage discovery, partition map.");
    report("NOT IMPLEMENTED: Hakchi installation identification, firmware extraction, backup verification.");
    report("NOT VERIFIED: restore/recovery or game installation.");
    report("COMPLETE: All currently feasible non-destructive checks attempted.");
    report("Export JSON report to save these results.");
  }
  void verifyStorageLoaderCompatibilityGate(){
    report("--- v1.5 console profile / loader safety gate ---");
    UsbDevice d=device;
    if(d==null||!manager.hasPermission(d)){report("BLOCKED: USB permission required");return;}
    UsbInterface iface=null;UsbEndpoint in=null,out=null;
    for(int i=0;i<d.getInterfaceCount();i++){
      UsbInterface it=d.getInterface(i);UsbEndpoint a=null,b=null;
      for(int j=0;j<it.getEndpointCount();j++){
        UsbEndpoint ep=it.getEndpoint(j);
        if(ep.getType()!=UsbConstants.USB_ENDPOINT_XFER_BULK)continue;
        if(ep.getDirection()==UsbConstants.USB_DIR_IN)a=ep;else b=ep;
      }
      if(a!=null&&b!=null){iface=it;in=a;out=b;break;}
    }
    if(iface==null){report("FAIL: FEL interface unavailable");return;}
    UsbDeviceConnection c=manager.openDevice(d);
    if(c==null){report("FAIL: Could not open USB connection");return;}
    boolean claimed=false;
    try{
      claimed=c.claimInterface(iface,false);
      if(!claimed)throw new Exception("Cannot claim FEL interface");
      felWriteRequest(c,out,in);
      byte[] version=felRead(c,out,in,32);
      byte[] status=felRead(c,out,in,8);
      if(!"AWUSBFEX".equals(new String(version,0,8,StandardCharsets.US_ASCII)))
        throw new Exception("FEL signature mismatch");
      if((status[0]&255)!=255||(status[1]&255)!=255)
        throw new Exception("Unexpected FEL response status");
      ByteBuffer b=ByteBuffer.wrap(version).order(ByteOrder.LITTLE_ENDIAN);
      int soc=(b.getInt(8)>>>8)&65535;
      int protocol=b.getShort(16)&65535;
      long scratch=b.getInt(20)&0xffffffffL;
      report(String.format(Locale.US,"Observed device profile: SoC 0x%04X, FEL protocol %d, scratch 0x%08X",soc,protocol,scratch));
      boolean expected=d.getVendorId()==VID&&d.getProductId()==PID&&soc==0x1667&&protocol==1&&scratch==0x7e00;
      report(expected?"PASS: Observed FEL profile matches expected NES Classic probe profile":"WARNING: Unexpected FEL profile; do not attempt loader operations");
      report("Loader allowlist: EMPTY (no validated loader binaries bundled)");
      report("Loader execution: DISABLED regardless of profile match");
      report("Internal storage access: BLOCKED until a validated loader and read-only protocol are implemented");
    }catch(Exception e){report("FAIL: Profile verification: "+e.getMessage());}
    finally{if(claimed)c.releaseInterface(iface);c.close();}
  }
  void storageDiscoveryPreflight(){
    report("--- v1.4 storage discovery prerequisites (read-only) ---");
    UsbDevice d=device;
    if(d==null||!manager.hasPermission(d)){
      report("BLOCKED: Storage prerequisite inspection requires USB permission.");
      return;
    }
    int bulkIn=0,bulkOut=0;
    for(int i=0;i<d.getInterfaceCount();i++){
      UsbInterface it=d.getInterface(i);
      for(int j=0;j<it.getEndpointCount();j++){
        UsbEndpoint ep=it.getEndpoint(j);
        if(ep.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK){
          if(ep.getDirection()==UsbConstants.USB_DIR_IN)bulkIn++;
          else bulkOut++;
        }
      }
    }
    report("USB bulk endpoints: "+bulkIn+" IN, "+bulkOut+" OUT");
    report("Boot ROM FEL transport: "+(bulkIn>0&&bulkOut>0?"AVAILABLE":"MISSING"));
    report("Storage discovery mode: NOT AVAILABLE via standard FEL version/read commands");
    report("Loader compatibility: UNVERIFIED for Allwinner R16/A33 (SoC 0x1667)");
    report("Storage medium: UNKNOWN; no NAND/eMMC identification attempted");
    report("NEXT REQUIRED IMPLEMENTATION: vetted, console-specific read-only RAM loader and protocol.");
    report("No executable uploaded to console RAM and no storage command issued.");
  }
  void storageAccessGateV19(){
    report("--- v1.9 internal storage access safety gate ---");
    boolean usb=device!=null&&manager.hasPermission(device);
    report("USB FEL device permission: "+(usb?"PASS":"BLOCKED"));
    report("Boot ROM FEL version and scratch RAM access: "+(usb?"AVAILABLE; see audit results":"NOT AVAILABLE"));
    report("Reviewed console-specific storage loader: MISSING");
    report("Verified loader hash allowlist: EMPTY");
    report("Documented read-only storage command protocol: MISSING");
    report("NAND/eMMC controller initialization: NOT IMPLEMENTED");
    report("Read-only storage capacity and identity: NOT DETECTED");
    report("Partition enumeration: BLOCKED");
    report("DECISION: DENY loader execution and storage access until all prerequisites are validated.");
    report("No storage commands, RAM uploads, firmware changes or writes performed.");
  }
  void storageDiscoveryGateV21(){
    report("--- v2.1 live storage discovery gate ---");
    boolean connected=device!=null&&manager.hasPermission(device);
    report("FEL USB permission: "+(connected?"GRANTED":"NOT GRANTED"));
    report("Known SoC profile from FEL: 0x1667 (see live identification above)");
    report("Storage device identification protocol: UNAVAILABLE");
    report("Loader provenance, memory map, command behavior and recovery: UNVERIFIED");
    report("Storage media type: UNKNOWN (do not infer NAND or eMMC from bootloader strings)");
    report("Storage capacity: UNKNOWN; partition count: UNKNOWN");
    report("Result: BLOCKED - actual storage detection has not been implemented.");
    report("Safety: no executable transfers, memory writes, storage reads or flashing.");
  }
  void auditCapabilities(){
    report("--- Read-only capability audit ---");
    boolean fel=device!=null&&manager.hasPermission(device);
    report("USB permission: "+(fel?"AVAILABLE":"BLOCKED"));
    report("Boot ROM transport: "+(fel?"TESTED ABOVE":"BLOCKED"));
    report("Internal storage enumeration: UNSUPPORTED (no FEL storage loader)");
    report("NAND/eMMC controller access: UNSUPPORTED (requires device-specific RAM loader)");
    report("Partition map: BLOCKED (storage not accessible)");
    report("Existing Hakchi install: BLOCKED (filesystem not accessible)");
    report("Firmware extraction: BLOCKED (storage not accessible)");
    report("Backup SHA-256 integrity: BLOCKED (no firmware image to verify)");
    report("Recovery compatibility: NOT TESTED (would require verified recovery path)");
    report("Game installation compatibility: NOT TESTED (no filesystem access)");
    report("SAF JSON export: AVAILABLE via Export JSON report button");
    report("SAF firmware backup export: NOT IMPLEMENTED");
    report("SAF firmware restore: DISABLED");
    report("SAFETY: No writes, loader execution, firmware extraction, or recovery attempted.");
  }
  void softwarePreflight(){
    report("--- Software / backup preparation checks ---");
    try{
      byte[] header=usbHeader(0x12,16);
      ByteBuffer b=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
      if(header.length!=32||header[0]!='A'||header[1]!='W'||header[2]!='U'||header[3]!='C'
          ||b.getInt(8)!=16||b.getShort(16)!=0x12||b.getInt(18)!=16)
        throw new Exception("AWUC packet header mismatch");
      report("PASS: FEL USB packet encoding self-test");
    }catch(Exception e){report("FAIL: Packet self-test: "+e.getMessage());}
    try{
      String sample="test \"quote\" \\ slash\\nline";
      String escaped=jsonEscape(sample);
      if(!escaped.contains("\\\"quote\\\"")||!escaped.contains("\\\\"))
        throw new Exception("JSON escaping mismatch");
      MessageDigest md=MessageDigest.getInstance("SHA-256");
      byte[] digest=md.digest(sample.getBytes(StandardCharsets.UTF_8));
      if(digest.length!=32)throw new Exception("SHA-256 digest length mismatch");
      report("PASS: JSON escaping and SHA-256 availability self-test");
    }catch(Exception e){report("FAIL: Report integrity preflight: "+e.getMessage());}
    try{
      StatFs fs=new StatFs(getFilesDir().getAbsolutePath());
      long available=fs.getAvailableBytes();
      report(String.format(Locale.US,"INFO: App storage available %.1f MiB (not a backup capacity guarantee)",available/1048576.0));
    }catch(Exception e){report("INFO: Storage capacity unavailable: "+e.getMessage());}
    report("PASS: Android device "+Build.MANUFACTURER+" "+Build.MODEL+", API "+Build.VERSION.SDK_INT);
    report("NOT VERIFIED: Backup file readback, checksum or restore (no firmware backup exists).");
  }
  void fullDiagnostics(){
    UsbDevice d=device;
    report("=== FEL Probe v0.9 full read-only diagnostics ===");
    if(d==null||!manager.hasPermission(d)){report("FAIL: Connect FEL device and grant USB permission");return;}
    report(String.format(Locale.US,"USB: %04X:%04X; interfaces: %d",d.getVendorId(),d.getProductId(),d.getInterfaceCount()));
    UsbInterface iface=null;UsbEndpoint in=null,out=null;
    for(int i=0;i<d.getInterfaceCount();i++){
      UsbInterface it=d.getInterface(i);UsbEndpoint a=null,b=null;
      for(int k=0;k<it.getEndpointCount();k++){
        UsbEndpoint ep=it.getEndpoint(k);
        if(ep.getType()!=UsbConstants.USB_ENDPOINT_XFER_BULK)continue;
        if(ep.getDirection()==UsbConstants.USB_DIR_IN)a=ep;else b=ep;
      }
      if(a!=null&&b!=null){iface=it;in=a;out=b;break;}
    }
    if(iface==null){report("FAIL: No bulk USB interface");return;}
    report(String.format(Locale.US,"USB endpoints: OUT 0x%02X, IN 0x%02X",out.getAddress(),in.getAddress()));
    UsbDeviceConnection c=manager.openDevice(d);
    if(c==null){report("FAIL: Cannot open USB device");return;}
    boolean claimed=false;
    try{
      claimed=c.claimInterface(iface,false);
      if(!claimed)throw new Exception("Cannot claim interface");
      byte[] baseline=null;long scratch=0;int soc=0,protocol=0;
      for(int i=1;i<=3;i++){
        felWriteRequest(c,out,in);
        byte[] version=felRead(c,out,in,32);
        byte[] status=felRead(c,out,in,8);
        if(!"AWUSBFEX".equals(new String(version,0,8,StandardCharsets.US_ASCII)))
          throw new Exception("Invalid FEL signature");
        if(status[0]!=(byte)0xff||status[1]!=(byte)0xff)
          throw new Exception("Unexpected FEL status");
        if(baseline!=null&&!Arrays.equals(baseline,version))
          throw new Exception("Inconsistent FEL version data");
        baseline=version;
        ByteBuffer b=ByteBuffer.wrap(version).order(ByteOrder.LITTLE_ENDIAN);
        soc=(b.getInt(8)>>>8)&0xffff;
        protocol=b.getShort(16)&0xffff;
        scratch=b.getInt(20)&0xffffffffL;
        report("PASS: FEL version request "+i+"/3");
      }
      report(String.format(Locale.US,"SoC 0x%04X; protocol %d; scratch 0x%08X",soc,protocol,scratch));
      if(scratch==0||scratch>0x100000||scratch%4!=0)
        throw new Exception("Scratch RAM address outside conservative bounds");
      byte[] first=null;
      for(int i=1;i<=3;i++){
        ByteBuffer req=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        req.putInt(0x103);req.putInt((int)scratch);req.putInt(16);req.putInt(0);
        send(c,out,usbHeader(0x12,16));
        send(c,out,req.array());
        usbStatus(c,in);
        byte[] data=felRead(c,out,in,16);
        byte[] status=felRead(c,out,in,8);
        if(status[0]!=(byte)0xff||status[1]!=(byte)0xff)
          throw new Exception("Unexpected RAM read status");
        if(first!=null&&!Arrays.equals(first,data))
          throw new Exception("Scratch RAM reads differ");
        first=data;
        report("PASS: scratch RAM read "+i+"/3");
      }
      report("Scratch RAM bytes: "+hex(first));
      report("PASS: Read-only FEL transport and RAM checks");
      report("NOT TESTED: firmware storage, partitions, Hakchi installation, backup, restore");
      report("NEXT: Tap 10 to export this session's diagnostics to JSON.");
    }catch(Exception e){report("FAIL: Full diagnostics: "+e.getMessage());}
    finally{if(claimed)c.releaseInterface(iface);c.close();}
  }
  @Override protected void onDestroy(){unregisterReceiver(receiver);super.onDestroy();}
}
