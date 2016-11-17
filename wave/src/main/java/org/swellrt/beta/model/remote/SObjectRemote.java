package org.swellrt.beta.model.remote;

import java.util.Collections;

import org.swellrt.beta.model.IllegalCastException;
import org.swellrt.beta.model.SMap;
import org.swellrt.beta.model.SNode;
import org.swellrt.beta.model.SObject;
import org.swellrt.beta.model.SPrimitive;
import org.swellrt.beta.model.js.Proxy;
import org.swellrt.beta.model.js.SMapProxyHandler;
import org.swellrt.beta.model.local.SMapLocal;
import org.swellrt.beta.model.remote.wave.DocumentBasedBasicRMap;
import org.waveprotocol.wave.model.adt.ObservableBasicMap;
import org.waveprotocol.wave.model.document.Doc.E;
import org.waveprotocol.wave.model.document.ObservableDocument;
import org.waveprotocol.wave.model.document.util.DefaultDocEventRouter;
import org.waveprotocol.wave.model.document.util.DocHelper;
import org.waveprotocol.wave.model.id.IdGenerator;
import org.waveprotocol.wave.model.id.ModernIdSerialiser;
import org.waveprotocol.wave.model.id.WaveletId;
import org.waveprotocol.wave.model.util.Preconditions;
import org.waveprotocol.wave.model.util.Serializer;
import org.waveprotocol.wave.model.wave.InvalidParticipantAddress;
import org.waveprotocol.wave.model.wave.ObservableWavelet;
import org.waveprotocol.wave.model.wave.ParticipantId;
import org.waveprotocol.wave.model.wave.opbased.ObservableWaveView;


/**
 * 
 * Main class providing object-based data model built on top of Wave data model.
 * <p>
 * A {@link SObject} represents a JSON-like data structure containing a nested structure of {@link SMap}, {@link SList} and {@link SPrimitive} values. 
 * <p>
 * The underlying implementation based on the Wave data model provides:
 * <ul>
 * <li>Real-time sync of SObject state from changes of different users, even between different servers (see Wave Federation)</li>
 * <li>In-flight persistence of changes with eventual consistency (see Wave Operational Transformations)</li>
 * </ul>
 * <p>
 * The SObject data model is mapped with Wave data model as follows:
 * <p>
 * Wave => SObject instance
 * <br>
 * Wavelet => Container
 * <br>
 * Blip/Document => Substrate
 * <br>
 * <p>
 * Implementation details based on the Wave data model follows:
 * <p>
 * Each node of a CObject is stored in a substrate Wave blip/document. Blips could be stored in different container Wavelets for
 * performance reasons if it is necessary.
 * <br>
 * A map (see example below) is represented as a following XML structure within a blip. Primitive values are embedded in their container structure,
 * and nested containers {@link SMap} or {@link SList} are represented as pointers to its substrate blip. 
 * <pre>
 * {@code
 *  
 *    prop1 : "value1",
 *    prop2 : 12345,
 *    prop3 : 
 *              prop31: "value31"
 *              prop32: "value32"
 *            
 *     prop4 : [ "a", "b", "c" ]
 *  
 * 
 * 
 *  <object>
 *    <prop1 t='s'>value1</prop1>
 *    <prop2 t='i'>12345</prop2>
 *    <prop3 t='o'>
 *          <prop31 t='s'>value31</prop31>
 *          <prop32 t='s'>value32</prop32>
 *    </prop3>
 *    <prop4 t='a'>
 *          <item t='s'>a</item>
 *          <item t='s'>b</item>
 *          <item t='s'>c</item>
 *    </prop4>
 *  </object>
 * 
 * }
 * </pre>
 * @author pablojan@gmail.com (Pablo Ojanguren)
 *
 */
public class SObjectRemote implements SObject, SNodeRemote {

  
  /**
   * A serializer/deserializer of CNode objects to/from a Wave concurrent map
   */
  public class SubstrateMapSerializer implements org.waveprotocol.wave.model.util.Serializer<SNodeRemote> {

    
    @Override
    public String toString(SNodeRemote x) {
    
      if (x instanceof SMapRemote) {
        SMapRemote map = (SMapRemote) x;  
        return map.getSubstrateId().serialize();
      }
      
      if (x instanceof SPrimitive) {        
        SPrimitive p = (SPrimitive) x;
        return p.serialize();
      }
      
      return null;
    }

    @Override
    public SNodeRemote fromString(String s) {            
      Preconditions.checkNotNull(s, "Unable to deserialize a null value");    
     
      SubstrateId substrateId = SubstrateId.deserialize(s);
      if (substrateId != null) {
        if (substrateId.isMap())
          return loadMap(substrateId);
      }
      
      SPrimitive v = SPrimitive.deserialize(s);      
      return v;      
    }

    @Override
    public SNodeRemote fromString(String s, SNodeRemote defaultValue) {
      return fromString(s);
    }
    
    
  }
  
  private static final String MASTER_DATA_WAVELET_NAME = "data+master";
  private static final String ROOT_SUBSTRATE_ID = "m+root";
  
  private static final String MAP_TAG = "map";
  private static final String MAP_ENTRY_TAG = "entry";
  private static final String MAP_ENTRY_KEY_ATTR = "k";
  private static final String MAP_ENTRY_VALUE_ATTR = "v";
  
  private final String domain;
  private final IdGenerator idGenerator;
  private final ObservableWaveView wave;
  private ObservableWavelet masterWavelet;
  
  private SubstrateMapSerializer mapSerializer;   
  private SMapRemote root;
  
  /**
   * Get a MutableCObject instance with a substrate Wave.
   * Initialize the Wave accordingly.
   * 
   */
  public static SObjectRemote inflateFromWave(IdGenerator idGenerator, String domain, ObservableWaveView wave) {
    
    Preconditions.checkArgument(domain != null && !domain.isEmpty(), "Domain is not provided");
    Preconditions.checkArgument(wave != null, "Wave can't be null");
    
    // Initialize master Wavelet if necessary    
    ObservableWavelet masterWavelet = wave.getWavelet(WaveletId.of(domain, MASTER_DATA_WAVELET_NAME));
    if (masterWavelet == null) {
      masterWavelet = wave.createWavelet(WaveletId.of(domain, MASTER_DATA_WAVELET_NAME));
    }
     
    SObjectRemote object = new SObjectRemote(idGenerator, domain, wave);
    object.init();
    
    return object;
  }
  
  /**
   * Private constructor.
   * 
   * @param idGenerator
   * @param domain
   * @param wave
   */
  private SObjectRemote(IdGenerator idGenerator, String domain, ObservableWaveView wave) {
    this.wave = wave;
    this.masterWavelet = wave.getWavelet(WaveletId.of(domain, MASTER_DATA_WAVELET_NAME));
    this.mapSerializer = new SubstrateMapSerializer();
    this.domain = domain;
    this.idGenerator = idGenerator;
  }
  
  /**
   * Initialization tasks not suitable for constructors.
   */
  private void init() {
    root = loadMap(SubstrateId.ofMap(masterWavelet.getId(), ROOT_SUBSTRATE_ID));
  }
  
  /**
   * Transform a SNode object to SNodeRemote. Check first if the node is already a SNodeRemote. 
   * Transform otherwise.
   * <p>
   * If node is already attached to a mutable node, this method should raise an
   * exception.
   * 
   * @param node
   * @param newContainer
   * @return
   */
  protected SNodeRemote asRemote(SNode node, boolean newContainer) {

    if (node instanceof SNodeRemote)
      return (SNodeRemote) node;

    ObservableWavelet containerWavelet = masterWavelet;

    if (newContainer) {
      containerWavelet = createContainerWavelet();
    }

    return asRemote(node, containerWavelet);
  }
  
  /**
   * Unit test only.
   * TODO fix visibility
   */
  public void clearCache() {
    root.clearCache();
  }
  
  /**
   * Transform a local SNode object to SNodeRemote recursively.
   * 
   * @param node
   * @param containerWavelet
   * @return
   */
  private SNodeRemote asRemote(SNode node, ObservableWavelet containerWavelet) {
        
    if (node instanceof SMapLocal) {
      SMapLocal localMap = (SMapLocal) node;
      SMapRemote remoteMap = loadMap(SubstrateId.createForMap(containerWavelet.getId(), idGenerator));
      
      for (String k: localMap.keys()) {
        SNode v = localMap.getNode(k);
        remoteMap.put(k, v);
      }
      
      return remoteMap;
    }
    
    if (node instanceof SPrimitive) {
      return (SPrimitive) node;
    }
    
    return null;
  }
  
  private ObservableWavelet createContainerWavelet() {
    return wave.createWavelet();
  }
  
  /**
   * Materialize a SMapRemote from a substrate Blip/Document (substrate)
   * of a Wavelet (container). <br>The underlying substrate is created if it doesn't exist yet.
   * The underlying Wavelet must exists.
   * <p>
   * TODO: manage auto-creation of Wavelets? 
   * 
   * @param substrateId
   * @return
   */
  private SMapRemote loadMap(SubstrateId substrateId) {
    Preconditions.checkArgument(substrateId.isMap(), "Expected a map susbtrate id");
    
    ObservableWavelet substrateContainer = wave.getWavelet(substrateId.getContainerId());
    ObservableDocument document = substrateContainer.getDocument(substrateId.getDocumentId());    
    DefaultDocEventRouter router = DefaultDocEventRouter.create(document);
    
    E mapElement = DocHelper.getElementWithTagName(document, MAP_TAG);
    if (mapElement == null) {
      mapElement = document.createChildElement(document.getDocumentElement(), MAP_TAG,
          Collections.<String, String> emptyMap());
    }
    
    ObservableBasicMap<String, SNodeRemote> map =
        DocumentBasedBasicRMap.create(router, 
            mapElement, 
            Serializer.STRING,
            mapSerializer, 
            MAP_ENTRY_TAG, 
            MAP_ENTRY_KEY_ATTR, 
            MAP_ENTRY_VALUE_ATTR);

    
    return SMapRemote.create(this, substrateId, map);     
  }
  
  @Override
  public String getId() {
    return ModernIdSerialiser.INSTANCE.serialiseWaveId(wave.getWaveId());
  }

  @Override
  public void addParticipant(String participantId) throws InvalidParticipantAddress {
    masterWavelet.addParticipant(ParticipantId.of(participantId));
  }

  @Override
  public void removeParticipant(String participantId) throws InvalidParticipantAddress {
    masterWavelet.removeParticipant(ParticipantId.of(participantId));
  }

  @Override
  public String[] getParticipants() {
    String[] array = new String[masterWavelet.getParticipantIds().size()];
    int i = 0;
    for (ParticipantId p: masterWavelet.getParticipantIds()) {
      array[i++] = p.getAddress();
    }
    return array;
  }

  @Override
  public Object asNative() {
    return new Proxy(root, new SMapProxyHandler());
  }
  
  
  //
  // SMap interface
  //
    
  

  @Override
  public Object get(String key) {
    return root.get(key);
  }

  @Override
  public SNode getNode(String key) {
    return root.getNode(key);
  }

  @Override
  public SMap put(String key, SNode value) {
    return root.put(key, value);
  }

  @Override
  public SMap put(String key, Object object) throws IllegalCastException {
    return root.put(key, object);
  }

  @Override
  public void remove(String key) {
    root.remove(key);
  }

  @Override
  public boolean has(String key) {
    return root.has(key);
  }

  @Override
  public String[] keys() {
    return root.keys();
  }

  @Override
  public void clear() {
    root.clear();
  }

  @Override
  public boolean isEmpty() {
    return root.isEmpty();
  }

  @Override
  public int size() {
    return root.size();
  }

}