package com.example.hakchifelprobe;

import android.app.*;
import android.os.*;
import android.content.*;
import android.hardware.usb.*;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import java.util.*;

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
  @Override protected void onDestroy(){unregisterReceiver(receiver);super.onDestroy();}
}
