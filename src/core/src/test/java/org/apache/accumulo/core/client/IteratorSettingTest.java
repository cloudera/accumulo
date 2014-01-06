package org.apache.accumulo.core.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.apache.accumulo.core.iterators.Combiner;
import org.apache.accumulo.core.iterators.DevNull;
import org.junit.Test;

/**
 * Test cases for the IteratorSetting class
 */
public class IteratorSettingTest {
  
  IteratorSetting setting1 = new IteratorSetting(500, "combiner", Combiner.class.getName());
  IteratorSetting setting2 = new IteratorSetting(500, "combiner", Combiner.class.getName());
  IteratorSetting setting3 = new IteratorSetting(500, "combiner", Combiner.class.getName());
  IteratorSetting devnull = new IteratorSetting(500, "devNull", DevNull.class.getName());
  IteratorSetting nullsetting = null;
  IteratorSetting setting4 = new IteratorSetting(300, "combiner", Combiner.class.getName());
  IteratorSetting setting5 = new IteratorSetting(500, "foocombiner", Combiner.class.getName());
  IteratorSetting setting6 = new IteratorSetting(500, "combiner", "MySuperCombiner");
  
  @Test
  public final void testHashCodeSameObject() {
    assertEquals(setting1.hashCode(), setting1.hashCode());
  }
  
  @Test
  public final void testHashCodeEqualObjects() {
    assertEquals(setting1.hashCode(), setting2.hashCode());
  }
  
  @Test
  public final void testEqualsObjectReflexive() {
    assertEquals(setting1, setting1);
  }
  
  @Test
  public final void testEqualsObjectSymmetric() {
    assertEquals(setting1, setting2);
    assertEquals(setting2, setting1);
  }
  
  @Test
  public final void testEqualsObjectTransitive() {
    assertEquals(setting1, setting2);
    assertEquals(setting2, setting3);
    assertEquals(setting1, setting3);
  }
  
  @Test
  public final void testEqualsNullSetting() {
    assertNotEquals(setting1, nullsetting);
  }
  
  @Test
  public final void testEqualsObjectNotEqual() {
    assertNotEquals(setting1, devnull);
  }
  
  @Test
  public final void testEqualsObjectProperties() {
    IteratorSetting mysettings = new IteratorSetting(500, "combiner", Combiner.class.getName());
    assertEquals(setting1, mysettings);
    mysettings.addOption("myoption1", "myvalue1");
    assertNotEquals(setting1, mysettings);
  }
  
  @Test
  public final void testEqualsDifferentMembers() {
    assertNotEquals(setting1, setting4);
    assertNotEquals(setting1, setting5);
    assertNotEquals(setting1, setting6);
  }
}
