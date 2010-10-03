package net.ire.regex;

import net.ire.DFARopePatternSet;
import net.ire.PatternSet;
import net.ire.fa.*;
import net.ire.util.WrappedBitSet;
import net.ire.util.Pair;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static net.ire.util.CollectionFactory.*;

/**
 * Created on: 01.09.2010 23:43:07
 */
public class RegexCompiler {
    public static PatternSet compile(List<RxNode> roots) {
        return new DFARopePatternSet(compileToBiDFA(roots));
    }

    public static PatternSet compile(String... regexes) {
        List<RxNode> roots = newArrayList();
        for(String regex : regexes) {
            roots.add(RegexParser.parse(regex));
        }
        return compile(roots);
    }

    static BiDFA<Character, PowerIntState> compileToBiDFA(List<RxNode> roots) {
        List<RxNode> rootsAnywhere = newArrayList();
        List<RxNode> reversedRoots = newArrayList();
        for(RxNode root : roots) {
            Alternative dotStar = new Alternative(new Empty(), new OnceOrMore(CharacterClass.ANY_CHAR));
            rootsAnywhere.add(new Sequence(dotStar, new Sequence(root, dotStar)));
            reversedRoots.add(new Sequence(reverse(root), dotStar));
        }
        return new BiDFA<Character, PowerIntState>(compileToDFA(rootsAnywhere), compileToDFA(reversedRoots));
    }

    static DFA<Character, PowerIntState> compileToDFA(List<RxNode> rxNodes) {
        if(rxNodes.isEmpty()) {
            throw new IllegalArgumentException("Pattern list can't be empty");
        }
        List<RxNode> labeled = newArrayList();
        for(int i = 0; i < rxNodes.size(); ++i) {
            labeled.add(new Labeled(rxNodes.get(i), i));
        }
        RxNode alt = labeled.get(0);
        for(int i = 1; i < rxNodes.size(); ++i) {
            alt = new Alternative(alt, labeled.get(i));
        }

        return toDFA(reduceNFA(toNFA(alt)), rxNodes.size());
    }

    static DFA<Character, PowerIntState> toDFA(NFA nfa, int numPatterns) {
        Pair<Set<NFA.Node>, NFA.Node> eClosure = computeEClosure(nfa);

        Set<NFA.Node> allNodes = eClosure.first;
        NFA.Node newInitial = eClosure.second;

        final int numStates = allNodes.size();

        final Map<NFA.Node, Integer> node2id = newLinkedHashMap();
        final NFA.Node[] id2node = allNodes.toArray(new NFA.Node[allNodes.size()]);

        for(int i = 0; i < id2node.length; ++i) {
            node2id.put(id2node[i], i);
        }

        final State[] basis = new State[numStates];
        for(int i = 0; i < numStates; ++i) {
            WrappedBitSet terminatedPatterns = new WrappedBitSet(numPatterns);
            for(int pat : id2node[i].patternIds) {
                terminatedPatterns.set(pat);
            }
            basis[i] = new IntState(i, terminatedPatterns);
        }

        TransferTable<Character,PowerIntState> transfer = new TransferTable<Character, PowerIntState>() {
            private TransferFunction[] transfer = new TransferFunction[Character.MAX_VALUE+1];

            public TransferFunction<PowerIntState> forToken(Character token) {
                char t = token;
                TransferFunction<PowerIntState> f = transfer[t];
                if(f == null) {
                    transfer[t] = f = computeTransferFor(t);
                }
                return f;
            }

            private TransferFunction<PowerIntState> computeTransferFor(char token) {
                WrappedBitSet[] state2next = new WrappedBitSet[numStates];
                for(int i = 0; i < numStates; ++i) {
                    WrappedBitSet res = new WrappedBitSet(numStates);
                    NFA.Node node = id2node[i];
                    for (Pair<CharacterClass, NFA.Node> out : node.out) {
                        if(out.first.acceptsChar(token)) {
                            res.set(node2id.get(out.second));
                        }
                    }
                    state2next[i] = res;
                }
                return new PowerIntTable(state2next);
            }
        };

        final WrappedBitSet justInitial = new WrappedBitSet(numStates);
        justInitial.set(node2id.get(newInitial));
        PowerIntState initial = new PowerIntState(basis, justInitial);

//        StringBuilder dot = new StringBuilder();
//        dot.append("digraph g {\n");
//        for(int i = 0; i < numStates; ++i) {
//            WrappedBitSet justThis = new WrappedBitSet(numStates);
//            justThis.set(i);
//            PowerIntState state = new PowerIntState(basis, justThis);
//            dot.append(i + " [shape=" + (state.getTerminatedPatterns().isEmpty() ? "circle" : "square") + "]\n");
//        }
//        for(int i = 0; i < numStates; ++i) {
//            WrappedBitSet justThis = new WrappedBitSet(numStates);
//            justThis.set(i);
//            PowerIntState state = new PowerIntState(basis, justThis);
//            PowerIntState nextState = transfer.forToken('t').next(state);
//            WrappedBitSet next = nextState.getSubset();
//            for(int bit = next.nextSetBit(0); bit != -1; bit = next.nextSetBit(bit+1)) {
//                dot.append(i + " -> " + bit + "\n");
//            }
//        }
//        dot.append("}\n");

        return new DFA<Character, PowerIntState>(transfer, initial, PowerIntTable.REDUCER) {
            @Override
            public PowerIntState resetTerminatedPattern(PowerIntState state, int pattern) {
                WrappedBitSet reset = new WrappedBitSet(basis.length);
                reset.or(state.getSubset());
                for(int substate = reset.nextSetBit(0); substate != -1; substate = reset.nextSetBit(substate + 1)) {
                    if(basis[substate].getTerminatedPatterns().get(pattern)) {
                        reset.clear(substate);
                    }
                }
                reset.or(justInitial);
                return new PowerIntState(basis, reset);
            }
        };
    }

    private static Pair<Set<NFA.Node>, NFA.Node> computeEClosure(NFA nfa) {
        final Map<NFA.Node, Set<NFA.Node>> node2closure = newLinkedHashMap();

        Set<NFA.Node> allOldNodes = dfs(nfa.begin, true);
        for(NFA.Node node : allOldNodes) {
            node2closure.put(node, dfs(node, false));
        }

        final Map<NFA.Node, Set<NFA.Node>> newNode2contents = newLinkedHashMap();
        final Map<Set<NFA.Node>, NFA.Node> contents2newNode = newLinkedHashMap();
        Set<NFA.Node> newNodesToVisit = newLinkedHashSet();
        Set<NFA.Node> initialEC = node2closure.get(nfa.begin);
        NFA.Node newInitial = new NFA.Node();
        for(NFA.Node subNode : initialEC) {
            newInitial.patternIds.addAll(subNode.patternIds);
        }
        newNodesToVisit.add(newInitial);
        newNode2contents.put(newInitial, initialEC);
        contents2newNode.put(initialEC, newInitial);
        while(!newNodesToVisit.isEmpty()) {
            NFA.Node newNode = newNodesToVisit.iterator().next();
            newNodesToVisit.remove(newNode);
            Map<CharacterClass, Set<NFA.Node>> class2dest = newLinkedHashMap();
            for(NFA.Node subNode : newNode2contents.get(newNode)) {
                for(Pair<CharacterClass, NFA.Node> out : subNode.out) {
                    if(out.first == null) {
                        // Skip epsilon transitions: we're operating on epsilon closures
                        continue;
                    }
                    Set<NFA.Node> dest = class2dest.get(out.first);
                    if(dest == null) {
                        class2dest.put(out.first, dest = newLinkedHashSet());
                    }
                    dest.addAll(node2closure.get(out.second));
                }
            }
            for(CharacterClass cc : class2dest.keySet()) {
                Set<NFA.Node> dest = class2dest.get(cc);
                NFA.Node newDest = contents2newNode.get(dest);
                if(newDest == null) {
                    newDest = new NFA.Node();
                    for(NFA.Node subNode : dest) {
                        newDest.patternIds.addAll(subNode.patternIds);
                    }
                    newNode2contents.put(newDest, dest);
                    contents2newNode.put(dest, newDest);
                    newNodesToVisit.add(newDest);
                }
                newNode.transition(cc, newDest);
            }
        }

        return Pair.of(newNode2contents.keySet(), newInitial);
    }

    static Set<NFA.Node> dfs(NFA.Node origin, boolean acceptNonEps) {
        Set<NFA.Node> res = newLinkedHashSet();
        Stack<NFA.Node> toVisit = new Stack<NFA.Node>();
        toVisit.add(origin);
        while(!toVisit.isEmpty()) {
            NFA.Node node = toVisit.pop();
            if(!res.add(node))
                continue;
            for (Pair<CharacterClass, NFA.Node> out : node.out) {
                if(out.first == null || acceptNonEps) {
                    toVisit.push(out.second);
                }
            }
        }
        return res;
    }

    static NFA reduceNFA(NFA nfa) {
        //
        return nfa;
        // TODO
    }

    static NFA toNFA(RxNode rxNode) {
        if(rxNode instanceof Alternative) {
            Alternative x = (Alternative) rxNode;
            NFA res = new NFA(new NFA.Node(), new NFA.Node());
            NFA a = toNFA(x.a), b = toNFA(x.b);
            res.begin.transition(null, a.begin);
            res.begin.transition(null, b.begin);
            a.end.transition(null, res.end);
            b.end.transition(null, res.end);
            return res;
        } else if(rxNode instanceof CharacterClass) {
            NFA res = new NFA(new NFA.Node(), new NFA.Node());
            res.begin.transition((CharacterClass) rxNode, res.end);
            return res;
        } else if(rxNode instanceof Empty) {
            NFA res = new NFA(new NFA.Node(), new NFA.Node());
            res.begin.transition(null, res.end);
            return res;
        } else if(rxNode instanceof OnceOrMore) {
            OnceOrMore x = (OnceOrMore) rxNode;
            NFA res = toNFA(x.a);
            res.end.transition(null, res.begin);
            return res;
        } else if(rxNode instanceof Sequence) {
            Sequence x = (Sequence) rxNode;
            NFA a = toNFA(x.a), b = toNFA(x.b);
            NFA res = new NFA(a.begin, b.end);
            a.end.transition(null, b.begin);
            return res;
        } else if(rxNode instanceof Labeled) {
            Labeled x = (Labeled) rxNode;
            NFA a = toNFA(x.a);
            a.end.patternIds.add(x.patternId);
            return a;
        } else {
            throw new UnsupportedOperationException("Unsupported node type " + rxNode.getClass());
        }
    }

    static class NFA {
        final Node begin, end;

        public NFA(Node begin, Node end) {
            this.begin = begin;
            this.end = end;
        }

        static class Node {
            static AtomicInteger nextId = new AtomicInteger(0);

            final List<Pair<CharacterClass, Node>> out = newArrayList();
            final Set<Integer> patternIds = newLinkedHashSet();
            final int id = nextId.incrementAndGet();

            void transition(CharacterClass cc, Node dest) {
                out.add(Pair.<CharacterClass, Node>of(cc, dest));
            }

            public String toString() {
                return ""+id;
            }

            public boolean equals(Object o) {
                return id == ((Node) o).id;
            }
            public int hashCode() {
                return id;
            }
        }
    }

    private static RxNode reverse(RxNode rxNode) {
        if(rxNode instanceof Alternative) {
            Alternative x = (Alternative) rxNode;
            return new Alternative(reverse(x.a), reverse(x.b));
        } else if(rxNode instanceof CharacterClass) {
            return rxNode;
        } else if(rxNode instanceof Empty) {
            return rxNode;
        } else if(rxNode instanceof OnceOrMore) {
            OnceOrMore x = (OnceOrMore) rxNode;
            return new OnceOrMore(reverse(x.a));
        } else if(rxNode instanceof Sequence) {
            Sequence x = (Sequence) rxNode;
            // The only interesting case.
            return new Sequence(reverse(x.b), reverse(x.a));
        } else if(rxNode instanceof Labeled) {
            Labeled x = (Labeled) rxNode;
            return new Labeled(reverse(x.a), x.patternId);
        } else {
            throw new UnsupportedOperationException("Unsupported node type " + rxNode.getClass());
        }
    }
}
