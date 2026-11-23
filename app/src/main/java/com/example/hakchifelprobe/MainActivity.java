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

public class MainActivity extends Activity {
  static final int VID=0x1F3A, PID=0xEFE8;
  static final String PERMISSION="com.example.hakchifelprobe.USB_PERMISSION";
  UsbManager manager;
  UsbDevice device;
  TextView status, log;
  Button permissionButton, probeButton;
  final BroadcastReceiver receiver = new BroadcastReceiver() {
    @Override public void onReceive(Context c, Intent i) {
      if (!PERMISSION.equals(i.getAction())) return;
      append(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false) ? "Permission granted" : "Permission denied");
      scan();
    }
  };
  @Override public void onCreate(Bundle b) {
    super.onCreate(b);
    manager=(UsbManager)getSystemService(USB_SERVICE);
    LinearLayout root=new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(22,30,22,20);
    root.setBackgroundColor(Color.rgb(13,19,30));
    TextView title=new TextView(this);
    title.setText("HAKCHI FEL PROBE"); title.setTextSize(24); title.setTextColor(Color.CYAN);
    root.addView(title);
    status=new TextView(this); status.setTextColor(Color.WHITE); status.setTextSize(16);
    status.setPadding(0,20,0,20); root.addView(status);
    button(root,"1. Scan USB",v->scan());
    permissionButton=button(root,"2. Request USB permission",v->request());
    probeButton=button(root,"3. Open FEL interface",v->probe());
    TextView note=new TextView(this);
    note.setText("Read-only connection test. No firmware writes or FEL commands. A successful claim does not prove FEL protocol communication.");
    note.setTextColor(Color.YELLOW); root.addView(note);
    ScrollView scroll=new ScrollView(this);
    log=new TextView(this); log.setTextColor(Color.GREEN); log.setTextIsSelectable(true);
    scroll.addView(log); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    button(root,"4. Read FEL version (read-only)",v->readFelVersion(1));
    button(root,"5. Reliability test: 5 version reads",v->readFelVersion(5));
    button(root,"6. Read 16 bytes of FEL scratch RAM",v->new Thread(()->readScratchRam(1)).start());
    button(root,"7. RAM reliability test: 5 reads",v->new Thread(()->readScratchRam(5)).start());
    button(root,"Share log",v->{Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_TEXT,log.getText().toString());startActivity(Intent.createChooser(i,"Share diagnostic log"));});
    setContentView(root);
    IntentFilter f=new IntentFilter(PERMISSION);
    if(Build.VERSION.SDK_INT>=33) registerReceiver(receiver,f,Context.RECEIVER_EXPORTED);
    else registerReceiver(receiver,f);
    scan();
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
  @Override protected void onDestroy(){unregisterReceiver(receiver);super.onDestroy();}
}
