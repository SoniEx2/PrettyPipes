package de.ellpeck.prettypipes.network;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Streams;
import de.ellpeck.prettypipes.PrettyPipes;
import de.ellpeck.prettypipes.Registry;
import de.ellpeck.prettypipes.Utility;
import de.ellpeck.prettypipes.misc.ItemEquality;
import de.ellpeck.prettypipes.packets.PacketHandler;
import de.ellpeck.prettypipes.packets.PacketItemEnterPipe;
import de.ellpeck.prettypipes.pipe.IPipeItem;
import de.ellpeck.prettypipes.pipe.PipeBlock;
import de.ellpeck.prettypipes.pipe.PipeBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import org.apache.commons.lang3.tuple.Pair;
import org.jgrapht.ListenableGraph;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.event.GraphEdgeChangeEvent;
import org.jgrapht.event.GraphListener;
import org.jgrapht.event.GraphVertexChangeEvent;
import org.jgrapht.graph.DefaultListenableGraph;
import org.jgrapht.graph.SimpleWeightedGraph;
import org.jgrapht.traverse.BreadthFirstIterator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PipeNetwork implements ICapabilitySerializable<CompoundTag>, GraphListener<BlockPos, NetworkEdge> {

    public final ListenableGraph<BlockPos, NetworkEdge> graph;
    private final DijkstraShortestPath<BlockPos, NetworkEdge> dijkstra;
    private final Map<BlockPos, List<BlockPos>> nodeToConnectedNodes = new HashMap<>();
    private final Map<BlockPos, PipeBlockEntity> tileCache = new HashMap<>();
    private final ListMultimap<BlockPos, IPipeItem> pipeItems = ArrayListMultimap.create();
    private final ListMultimap<BlockPos, NetworkLock> networkLocks = ArrayListMultimap.create();
    private final Level world;
    private final LazyOptional<PipeNetwork> lazyThis = LazyOptional.of(() -> this);

    public PipeNetwork(Level world) {
        this.world = world;
        this.graph = new DefaultListenableGraph<>(new SimpleWeightedGraph<>(NetworkEdge.class));
        this.graph.addGraphListener(this);
        this.dijkstra = new DijkstraShortestPath<>(this.graph);
    }

    @Nonnull
    @Override
    public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap, @Nullable Direction side) {
        return cap == Registry.pipeNetworkCapability ? this.lazyThis.cast() : LazyOptional.empty();
    }

    @Override
    public CompoundTag serializeNBT() {
        var nbt = new CompoundTag();
        var nodes = new ListTag();
        for (var node : this.graph.vertexSet())
            nodes.add(NbtUtils.writeBlockPos(node));
        nbt.put("nodes", nodes);
        var edges = new ListTag();
        for (var edge : this.graph.edgeSet())
            edges.add(edge.serializeNBT());
        nbt.put("edges", edges);
        nbt.put("items", Utility.serializeAll(this.pipeItems.values()));
        nbt.put("locks", Utility.serializeAll(this.networkLocks.values()));
        return nbt;
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        this.graph.removeAllVertices(new ArrayList<>(this.graph.vertexSet()));
        this.pipeItems.clear();
        this.networkLocks.clear();

        var nodes = nbt.getList("nodes", Tag.TAG_COMPOUND);
        for (var i = 0; i < nodes.size(); i++)
            this.graph.addVertex(NbtUtils.readBlockPos(nodes.getCompound(i)));
        var edges = nbt.getList("edges", Tag.TAG_COMPOUND);
        for (var i = 0; i < edges.size(); i++)
            this.addEdge(new NetworkEdge(edges.getCompound(i)));
        for (var item : Utility.deserializeAll(nbt.getList("items", Tag.TAG_COMPOUND), IPipeItem::load))
            this.pipeItems.put(item.getCurrentPipe(), item);
        for (var lock : Utility.deserializeAll(nbt.getList("locks", Tag.TAG_COMPOUND), NetworkLock::new))
            this.createNetworkLock(lock);
    }

    public void addNode(BlockPos pos, BlockState state) {
        if (!this.isNode(pos)) {
            this.graph.addVertex(pos);
            this.refreshNode(pos, state);
        }
    }

    public void removeNode(BlockPos pos) {
        if (this.isNode(pos))
            this.graph.removeVertex(pos);
    }

    public boolean isNode(BlockPos pos) {
        return this.graph.containsVertex(pos);
    }

    public void onPipeChanged(BlockPos pos, BlockState state) {
        var neighbors = this.createAllEdges(pos, state, true);
        // if we only have one neighbor, then there can't be any new connections
        if (neighbors.size() <= 1 && !this.isNode(pos))
            return;
        for (var edge : neighbors) {
            var end = edge.getEndPipe();
            this.refreshNode(end, this.world.getBlockState(end));
        }
    }

    public ItemStack routeItem(BlockPos startPipePos, BlockPos startInventory, ItemStack stack, boolean preventOversending) {
        return this.routeItem(startPipePos, startInventory, stack, PipeItem::new, preventOversending);
    }

    public ItemStack routeItem(BlockPos startPipePos, BlockPos startInventory, ItemStack stack, BiFunction<ItemStack, Float, IPipeItem> itemSupplier, boolean preventOversending) {
        if (!this.isNode(startPipePos))
            return stack;
        if (!this.world.isLoaded(startPipePos))
            return stack;
        var startPipe = this.getPipe(startPipePos);
        if (startPipe == null)
            return stack;
        this.startProfile("find_destination");
        var nodes = this.getOrderedNetworkNodes(startPipePos);
        for (var i = 0; i < nodes.size(); i++) {
            var pipePos = nodes.get(startPipe.getNextNode(nodes, i));
            if (!this.world.isLoaded(pipePos))
                continue;
            var pipe = this.getPipe(pipePos);
            var dest = pipe.getAvailableDestination(stack, false, preventOversending);
            if (dest == null || dest.getLeft().equals(startInventory))
                continue;
            var sup = (Function<Float, IPipeItem>) speed -> itemSupplier.apply(dest.getRight(), speed);
            if (this.routeItemToLocation(startPipePos, startInventory, pipe.getBlockPos(), dest.getLeft(), dest.getRight(), sup)) {
                var remain = stack.copy();
                remain.shrink(dest.getRight().getCount());
                this.endProfile();
                return remain;
            }
        }
        this.endProfile();
        return stack;
    }

    public boolean routeItemToLocation(BlockPos startPipePos, BlockPos startInventory, BlockPos destPipePos, BlockPos destInventory, ItemStack stack, Function<Float, IPipeItem> itemSupplier) {
        if (!this.isNode(startPipePos) || !this.isNode(destPipePos))
            return false;
        if (!this.world.isLoaded(startPipePos) || !this.world.isLoaded(destPipePos))
            return false;
        var startPipe = this.getPipe(startPipePos);
        if (startPipe == null)
            return false;
        this.startProfile("get_path");
        var path = this.dijkstra.getPath(startPipePos, destPipePos);
        this.endProfile();
        if (path == null)
            return false;
        var item = itemSupplier.apply(startPipe.getItemSpeed(stack));
        item.setDestination(startInventory, destInventory, path);
        startPipe.addNewItem(item);
        PacketHandler.sendToAllLoaded(this.world, startPipePos, new PacketItemEnterPipe(startPipePos, item));
        return true;
    }

    public ItemStack requestItem(BlockPos destPipe, BlockPos destInventory, ItemStack stack, ItemEquality... equalityTypes) {
        var remain = stack.copy();
        // check existing items
        for (var location : this.getOrderedNetworkItems(destPipe)) {
            remain = this.requestExistingItem(location, destPipe, destInventory, null, remain, equalityTypes);
            if (remain.isEmpty())
                return remain;
        }
        // check craftable items
        return this.requestCraftedItem(destPipe, null, remain, new Stack<>(), equalityTypes);
    }

    public ItemStack requestCraftedItem(BlockPos destPipe, Consumer<ItemStack> unavailableConsumer, ItemStack stack, Stack<ItemStack> dependencyChain, ItemEquality... equalityTypes) {
        for (var craftable : this.getAllCraftables(destPipe)) {
            if (!ItemEquality.compareItems(stack, craftable.getRight(), equalityTypes))
                continue;
            var pipe = this.getPipe(craftable.getLeft());
            if (pipe == null)
                continue;
            stack = pipe.craft(destPipe, unavailableConsumer, stack, dependencyChain);
            if (stack.isEmpty())
                break;
        }
        return stack;
    }

    public ItemStack requestExistingItem(NetworkLocation location, BlockPos destPipe, BlockPos destInventory, NetworkLock ignoredLock, ItemStack stack, ItemEquality... equalityTypes) {
        return this.requestExistingItem(location, destPipe, destInventory, ignoredLock, PipeItem::new, stack, equalityTypes);
    }

    public ItemStack requestExistingItem(NetworkLocation location, BlockPos destPipe, BlockPos destInventory, NetworkLock ignoredLock, BiFunction<ItemStack, Float, IPipeItem> itemSupplier, ItemStack stack, ItemEquality... equalityTypes) {
        if (location.getPos().equals(destInventory))
            return stack;
        // make sure we don't pull any locked items
        var amount = location.getItemAmount(this.world, stack, equalityTypes);
        if (amount <= 0)
            return stack;
        amount -= this.getLockedAmount(location.getPos(), stack, ignoredLock, equalityTypes);
        if (amount <= 0)
            return stack;
        var remain = stack.copy();
        // make sure we only extract less than or equal to the requested amount
        if (remain.getCount() < amount)
            amount = remain.getCount();
        remain.shrink(amount);
        for (int slot : location.getStackSlots(this.world, stack, equalityTypes)) {
            // try to extract from that location's inventory and send the item
            var handler = location.getItemHandler(this.world);
            var extracted = handler.extractItem(slot, amount, true);
            if (this.routeItemToLocation(location.pipePos, location.getPos(), destPipe, destInventory, extracted, speed -> itemSupplier.apply(extracted, speed))) {
                handler.extractItem(slot, extracted.getCount(), false);
                amount -= extracted.getCount();
                if (amount <= 0)
                    break;
            }
        }
        return remain;
    }

    public PipeBlockEntity getPipe(BlockPos pos) {
        var tile = this.tileCache.get(pos);
        if (tile == null || tile.isRemoved()) {
            tile = Utility.getBlockEntity(PipeBlockEntity.class, this.world, pos);
            this.tileCache.put(pos, tile);
        }
        return tile;
    }

    public void uncachePipe(BlockPos pos) {
        this.tileCache.remove(pos);
    }

    public List<Pair<BlockPos, ItemStack>> getCurrentlyCrafting(BlockPos node, ItemEquality... equalityTypes) {
        this.startProfile("get_currently_crafting");
        List<Pair<BlockPos, ItemStack>> items = new ArrayList<>();
        var craftingPipes = this.getAllCraftables(node).stream().map(c -> this.getPipe(c.getLeft())).distinct().iterator();
        while (craftingPipes.hasNext()) {
            var pipe = craftingPipes.next();
            for (var request : pipe.craftResultRequests) {
                var dest = request.getLeft();
                var stack = request.getRight();
                // add up all the items that should go to the same location
                var existing = items.stream()
                        .filter(s -> s.getLeft().equals(dest) && ItemEquality.compareItems(s.getRight(), stack, equalityTypes))
                        .findFirst();
                if (existing.isPresent()) {
                    existing.get().getRight().grow(stack.getCount());
                } else {
                    items.add(Pair.of(dest, stack.copy()));
                }
            }
        }
        this.endProfile();
        return items;
    }

    public int getCurrentlyCraftingAmount(BlockPos destNode, ItemStack stack, ItemEquality... equalityTypes) {
        return this.getCurrentlyCrafting(destNode).stream()
                .filter(p -> p.getLeft().equals(destNode) && ItemEquality.compareItems(p.getRight(), stack, equalityTypes))
                .mapToInt(p -> p.getRight().getCount()).sum();
    }

    public List<Pair<BlockPos, ItemStack>> getAllCraftables(BlockPos node) {
        if (!this.isNode(node))
            return Collections.emptyList();
        this.startProfile("get_all_craftables");
        List<Pair<BlockPos, ItemStack>> craftables = new ArrayList<>();
        for (var dest : this.getOrderedNetworkNodes(node)) {
            if (!this.world.isLoaded(dest))
                continue;
            var pipe = this.getPipe(dest);
            for (var stack : pipe.getAllCraftables())
                craftables.add(Pair.of(pipe.getBlockPos(), stack));
        }
        this.endProfile();
        return craftables;
    }

    public int getCraftableAmount(BlockPos node, Consumer<ItemStack> unavailableConsumer, ItemStack stack, Stack<ItemStack> dependencyChain, ItemEquality... equalityTypes) {
        var total = 0;
        for (var pair : this.getAllCraftables(node)) {
            if (!ItemEquality.compareItems(pair.getRight(), stack, equalityTypes))
                continue;
            if (!this.world.isLoaded(pair.getLeft()))
                continue;
            var pipe = this.getPipe(pair.getLeft());
            if (pipe != null)
                total += pipe.getCraftableAmount(unavailableConsumer, stack, dependencyChain);
        }
        return total;
    }

    public List<NetworkLocation> getOrderedNetworkItems(BlockPos node) {
        if (!this.isNode(node))
            return Collections.emptyList();
        this.startProfile("get_network_items");
        List<NetworkLocation> info = new ArrayList<>();
        for (var dest : this.getOrderedNetworkNodes(node)) {
            if (!this.world.isLoaded(dest))
                continue;
            var pipe = this.getPipe(dest);
            if (!pipe.canNetworkSee())
                continue;
            for (var dir : Direction.values()) {
                var handler = pipe.getItemHandler(dir);
                if (handler == null)
                    continue;
                // check if this handler already exists (double-connected pipes, double chests etc.)
                if (info.stream().anyMatch(l -> handler.equals(l.getItemHandler(this.world))))
                    continue;
                var location = new NetworkLocation(dest, dir);
                if (!location.isEmpty(this.world))
                    info.add(location);
            }
        }
        this.endProfile();
        return info;
    }

    public void createNetworkLock(NetworkLock lock) {
        this.networkLocks.put(lock.location.getPos(), lock);
    }

    public void resolveNetworkLock(NetworkLock lock) {
        this.networkLocks.remove(lock.location.getPos(), lock);
    }

    public List<NetworkLock> getNetworkLocks(BlockPos pos) {
        return this.networkLocks.get(pos);
    }

    public int getLockedAmount(BlockPos pos, ItemStack stack, NetworkLock ignoredLock, ItemEquality... equalityTypes) {
        return this.getNetworkLocks(pos).stream()
                .filter(l -> !l.equals(ignoredLock) && ItemEquality.compareItems(l.stack, stack, equalityTypes))
                .mapToInt(l -> l.stack.getCount()).sum();
    }

    private void refreshNode(BlockPos pos, BlockState state) {
        this.startProfile("refresh_node");
        this.graph.removeAllEdges(new ArrayList<>(this.graph.edgesOf(pos)));
        for (var edge : this.createAllEdges(pos, state, false))
            this.addEdge(edge);
        this.endProfile();
    }

    private void addEdge(NetworkEdge edge) {
        this.graph.addEdge(edge.getStartPipe(), edge.getEndPipe(), edge);
        // only use size - 1 so that nodes aren't counted twice for multi-edge paths
        this.graph.setEdgeWeight(edge, edge.pipes.size() - 1);
    }

    public BlockPos getNodeFromPipe(BlockPos pos) {
        if (this.isNode(pos))
            return pos;
        var state = this.world.getBlockState(pos);
        if (!(state.getBlock() instanceof PipeBlock))
            return null;
        for (var dir : Direction.values()) {
            var edge = this.createEdge(pos, state, dir, false);
            if (edge != null)
                return edge.getEndPipe();
        }
        return null;
    }

    private List<NetworkEdge> createAllEdges(BlockPos pos, BlockState state, boolean ignoreCurrBlocked) {
        this.startProfile("create_all_edges");
        List<NetworkEdge> edges = new ArrayList<>();
        for (var dir : Direction.values()) {
            var edge = this.createEdge(pos, state, dir, ignoreCurrBlocked);
            if (edge != null)
                edges.add(edge);
        }
        this.endProfile();
        return edges;
    }

    private NetworkEdge createEdge(BlockPos pos, BlockState state, Direction dir, boolean ignoreCurrBlocked) {
        if (!ignoreCurrBlocked && !state.getValue(PipeBlock.DIRECTIONS.get(dir)).isConnected())
            return null;
        var currPos = pos.relative(dir);
        var currState = this.world.getBlockState(currPos);
        if (!(currState.getBlock() instanceof PipeBlock))
            return null;
        this.startProfile("create_edge");
        var edge = new NetworkEdge();
        edge.pipes.add(pos);
        edge.pipes.add(currPos);

        while (true) {
            // if we found a vertex, we can stop since that's the next node
            // we do this here since the first offset pipe also needs to check this
            if (this.isNode(currPos)) {
                this.endProfile();
                return edge;
            }

            var found = false;
            for (var nextDir : Direction.values()) {
                if (!currState.getValue(PipeBlock.DIRECTIONS.get(nextDir)).isConnected())
                    continue;
                var offset = currPos.relative(nextDir);
                var offState = this.world.getBlockState(offset);
                if (!(offState.getBlock() instanceof PipeBlock))
                    continue;
                if (edge.pipes.contains(offset))
                    continue;
                edge.pipes.add(offset);
                currPos = offset;
                currState = offState;
                found = true;
                break;
            }
            if (!found)
                break;
        }
        this.endProfile();
        return null;
    }

    public List<BlockPos> getOrderedNetworkNodes(BlockPos node) {
        if (!this.isNode(node))
            return Collections.emptyList();
        var ret = this.nodeToConnectedNodes.get(node);
        if (ret == null) {
            this.startProfile("compile_connected_nodes");
            var paths = this.dijkstra.getPaths(node);
            // sort destinations first by their priority (eg trash pipes should be last)
            // and then by their distance from the specified node
            ret = Streams.stream(new BreadthFirstIterator<>(this.graph, node))
                    .filter(p -> this.getPipe(p) != null)
                    .sorted(Comparator.<BlockPos>comparingInt(p -> this.getPipe(p).getPriority()).reversed().thenComparing(paths::getWeight))
                    .collect(Collectors.toList());
            this.nodeToConnectedNodes.put(node, ret);
            this.endProfile();
        }
        return ret;
    }

    public void clearDestinationCache(BlockPos... nodes) {
        this.startProfile("clear_node_cache");
        // remove caches for the nodes
        for (var node : nodes)
            this.nodeToConnectedNodes.keySet().remove(node);
        // remove caches that contain the nodes as a destination
        this.nodeToConnectedNodes.values().removeIf(cached -> Arrays.stream(nodes).anyMatch(cached::contains));
        this.endProfile();
    }

    public List<IPipeItem> getItemsInPipe(BlockPos pos) {
        return this.pipeItems.get(pos);
    }

    public Stream<IPipeItem> getPipeItemsOnTheWay(BlockPos goalInv) {
        this.startProfile("get_pipe_items_on_the_way");
        var ret = this.pipeItems.values().stream().filter(i -> i.getDestInventory().equals(goalInv));
        this.endProfile();
        return ret;
    }

    public int getItemsOnTheWay(BlockPos goalInv, ItemStack type, ItemEquality... equalityTypes) {
        return this.getPipeItemsOnTheWay(goalInv)
                .filter(i -> type == null || ItemEquality.compareItems(i.getContent(), type, equalityTypes))
                .mapToInt(i -> i.getItemsOnTheWay(goalInv)).sum();
    }

    @Override
    public void edgeAdded(GraphEdgeChangeEvent<BlockPos, NetworkEdge> e) {
        this.clearDestinationCache(e.getEdgeSource(), e.getEdgeTarget());
    }

    @Override
    public void edgeRemoved(GraphEdgeChangeEvent<BlockPos, NetworkEdge> e) {
        this.clearDestinationCache(e.getEdgeSource(), e.getEdgeTarget());
    }

    @Override
    public void vertexAdded(GraphVertexChangeEvent<BlockPos> e) {
    }

    @Override
    public void vertexRemoved(GraphVertexChangeEvent<BlockPos> e) {
    }

    public void startProfile(String name) {
        this.world.getProfiler().push(() -> PrettyPipes.ID + ":pipe_network_" + name);
    }

    public void endProfile() {
        this.world.getProfiler().pop();
    }

    public static PipeNetwork get(Level world) {
        return world.getCapability(Registry.pipeNetworkCapability).orElse(null);
    }

}
