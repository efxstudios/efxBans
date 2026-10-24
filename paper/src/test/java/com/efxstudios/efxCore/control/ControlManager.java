package com.efxstudios.efxCore.control;
import org.bukkit.entity.Player;
import java.util.*;
/** Test fixture matching the actual optional integration method descriptors. */
public class ControlManager {
    public boolean control;
    public List<String> routed=new ArrayList<>();
    public boolean shouldRouteToControl(Player player){ return control; }
    public void routeControlMessage(Player player,String message){ routed.add(message); }
}
