package com.multiagent.discuss.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 席位上的**待保存清单** —— 2.5.0 加的「本场读了哪些文件、还没入库」那一层。
 *
 * <p>它同时干两件事：说明「结束会话该把哪些文件存进常驻知识库」，以及当**幂等闸**。
 * 两件事错了都很难被发现：前者错了会漏存或错存，后者错了会重复入库 ——
 * 而原始文件不入库，多存一份就是库里多一条永远删不干净、也重建不出来的重复记录。
 *
 * <p>它替代的是早先想用的一个 {@code uploadedFileName} 标记：那个做法在「传 B 失败」
 * 时会以 B 的名字把 A 的页存进库（{@code ingestAgentDocument} 在开头就写文件名，
 * 失败分支不清它，而 {@code putPages} 在失败时根本没执行）。清单只在完整成功的那一支追加。
 */
class DomainAgentConfigPendingDocsTest {

    @Test
    void drainIsTheIdempotencyGate() {
        DomainAgentConfig seat = seat();
        seat.addPendingDoc("a.pdf", List.of(page(0)));

        assertTrue(seat.hasPendingDocs());
        assertEquals(1, seat.drainPendingDocs().size());
        assertFalse(seat.hasPendingDocs(), "摘走即清空");
        assertTrue(seat.drainPendingDocs().isEmpty(),
                "★ 第二次必须是空的 —— 取消后重来 / 摘要失败重试都靠这一条不重复入库");
    }

    @Test
    void emptyDrainIsEmptyNotError() {
        assertTrue(seat().drainPendingDocs().isEmpty());
    }

    @Test
    void multipleUploadsAccumulateInOrder() {
        DomainAgentConfig seat = seat();
        seat.addPendingDoc("第一份.pdf", List.of(page(0)));
        seat.addPendingDoc("第二份.pdf", List.of(page(0), page(1)));

        List<DomainAgentConfig.PendingDoc> taken = seat.drainPendingDocs();

        assertEquals(2, taken.size(), "同一场传两份就是两条，各存各的（不覆盖）");
        assertEquals("第一份.pdf", taken.get(0).fileName());
        assertEquals("第二份.pdf", taken.get(1).fileName());
        assertEquals(2, taken.get(1).pages().size());
    }

    @Test
    void pagesAreSnapshottedNotReferenced() {
        // ★ putPages 是覆盖语义：同一席位再传一个文件就会把 store 里那份换掉。
        // 若清单存的是引用而不是快照，等到结束会话时才回读，中间那些文件会被静默丢掉 ——
        // 而原始字节不入库，丢了就救不回来。
        List<PageBlock> pages = new ArrayList<>();
        pages.add(page(0));
        DomainAgentConfig seat = seat();
        seat.addPendingDoc("a.pdf", pages);

        pages.add(page(1)); // 调用方随后改造了这个列表

        assertEquals(1, seat.drainPendingDocs().get(0).pages().size(), "清单里存的是当时的快照");
    }

    @Test
    void nullPagesDegradeToEmpty() {
        DomainAgentConfig seat = seat();
        seat.addPendingDoc("a.pdf", null);
        assertTrue(seat.drainPendingDocs().get(0).pages().isEmpty());
    }

    @Test
    void drainedListIsImmutable() {
        DomainAgentConfig seat = seat();
        seat.addPendingDoc("a.pdf", List.of(page(0)));
        List<DomainAgentConfig.PendingDoc> taken = seat.drainPendingDocs();
        try {
            taken.add(new DomainAgentConfig.PendingDoc("b.pdf", List.of()));
        } catch (UnsupportedOperationException expected) {
            return; // 调用方改不了这份返回值，也就影响不到席位的内部状态
        }
        assertEquals(1, taken.size(), "返回的必须是不可变拷贝");
    }

    private static DomainAgentConfig seat() {
        return new DomainAgentConfig("agent-1", "sess-1", "材料专家", "sk-x", "https://gw", "m", "persona");
    }

    private static PageBlock page(int index) {
        PageBlock b = new PageBlock();
        b.setPageIndex(index);
        return b;
    }
}
