package com.multiagent.discuss.domain;

import java.util.ArrayList;
import java.util.List;

public class PageBlock {

    private int pageIndex;
    private String imageRef;
    private String plainHint;
    /**
     * 该页所属的来源文件名。目前只有「导师模式」会上传多篇论文到同一个知识库，
     * 需要靠它把页码归属到具体论文；普通智能体单篇上传时可以不设。
     */
    private String sourceName;
    private Understanding understanding = new Understanding();

    public String getSourceName() {
        return sourceName;
    }

    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    public int getPageIndex() {
        return pageIndex;
    }

    public void setPageIndex(int pageIndex) {
        this.pageIndex = pageIndex;
    }

    public String getImageRef() {
        return imageRef;
    }

    public void setImageRef(String imageRef) {
        this.imageRef = imageRef;
    }

    public String getPlainHint() {
        return plainHint;
    }

    public void setPlainHint(String plainHint) {
        this.plainHint = plainHint;
    }

    public Understanding getUnderstanding() {
        return understanding;
    }

    public void setUnderstanding(Understanding understanding) {
        this.understanding = understanding;
    }

    /**
     * 页理解的上下文文本。**普通智能体的知识注入一直用它，输出保持原样不动。**
     */
    public String asContextSnippet() {
        return renderSnippet("【第 " + (pageIndex + 1) + " 页】\n");
    }

    /**
     * 与 {@link #asContextSnippet()} 内容完全一致，只把页头换成「《文件名》第 N 页」。
     *
     * <p>导师的私有知识库里混着多篇论文，引用必须能落到具体论文，否则「请给出论文名与页码」
     * 这条要求无法满足。没设 {@code sourceName} 时原样退回普通页头 —— 调用方不需要判空。
     */
    public String asContextSnippetWithSource() {
        if (sourceName == null || sourceName.isBlank()) {
            return asContextSnippet();
        }
        return renderSnippet("【《" + sourceName + "》第 " + (pageIndex + 1) + " 页】\n");
    }

    /** 两个公开方法共用的渲染体，只有页头不同。 */
    private String renderSnippet(String header) {
        Understanding u = understanding == null ? new Understanding() : understanding;
        StringBuilder sb = new StringBuilder();
        sb.append(header);
        if (u.getNarrative() != null) {
            sb.append(u.getNarrative()).append('\n');
        }
        if (u.getClaims() != null) {
            for (String c : u.getClaims()) {
                sb.append("- 结论：").append(c).append('\n');
            }
        }
        if (u.getTables() != null) {
            for (TableInfo t : u.getTables()) {
                sb.append("- 表：").append(t.getTitle() == null ? "" : t.getTitle()).append('\n');
                if (t.getMarkdown() != null) {
                    sb.append(t.getMarkdown()).append('\n');
                }
            }
        }
        if (u.getFigures() != null) {
            for (FigureInfo f : u.getFigures()) {
                sb.append("- 图：").append(f.getType() == null ? "" : f.getType())
                        .append(" / ").append(f.getKeyInfo() == null ? "" : f.getKeyInfo()).append('\n');
            }
        }
        if (u.getUncertainties() != null && !u.getUncertainties().isEmpty()) {
            sb.append("不确定点：").append(String.join("；", u.getUncertainties())).append('\n');
        }
        return sb.toString();
    }

    public static class Understanding {
        private String narrative;
        private List<TableInfo> tables = new ArrayList<>();
        private List<FigureInfo> figures = new ArrayList<>();
        private List<String> claims = new ArrayList<>();
        private List<String> uncertainties = new ArrayList<>();

        public String getNarrative() {
            return narrative;
        }

        public void setNarrative(String narrative) {
            this.narrative = narrative;
        }

        public List<TableInfo> getTables() {
            return tables;
        }

        public void setTables(List<TableInfo> tables) {
            this.tables = tables;
        }

        public List<FigureInfo> getFigures() {
            return figures;
        }

        public void setFigures(List<FigureInfo> figures) {
            this.figures = figures;
        }

        public List<String> getClaims() {
            return claims;
        }

        public void setClaims(List<String> claims) {
            this.claims = claims;
        }

        public List<String> getUncertainties() {
            return uncertainties;
        }

        public void setUncertainties(List<String> uncertainties) {
            this.uncertainties = uncertainties;
        }
    }

    public static class TableInfo {
        private String title;
        private String markdown;
        private String unit;
        private String note;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getMarkdown() {
            return markdown;
        }

        public void setMarkdown(String markdown) {
            this.markdown = markdown;
        }

        public String getUnit() {
            return unit;
        }

        public void setUnit(String unit) {
            this.unit = unit;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }
    }

    public static class FigureInfo {
        private String type;
        private String keyInfo;
        private String relationToText;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getKeyInfo() {
            return keyInfo;
        }

        public void setKeyInfo(String keyInfo) {
            this.keyInfo = keyInfo;
        }

        public String getRelationToText() {
            return relationToText;
        }

        public void setRelationToText(String relationToText) {
            this.relationToText = relationToText;
        }
    }
}
